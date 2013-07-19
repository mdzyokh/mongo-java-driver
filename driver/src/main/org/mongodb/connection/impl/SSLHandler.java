/**
 * Copyright [2012] [Gihan Munasinghe ayeshka@gmail.com ]
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */


package org.mongodb.connection.impl;


import java.io.FileInputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;

import org.bson.ByteBuf;
import org.mongodb.connection.BufferProvider;
import org.mongodb.connection.MongoSocketOpenException;
import org.mongodb.connection.MongoSocketReadException;
import org.mongodb.connection.MongoSocketWriteException;


public class SSLHandler {

    private SSLEngine sslEngine;
    private SocketChannel channel;

    private ByteBuffer sendBuffer = null;
    private ByteBuffer receiveBuffer = null;
    private ByteBuffer unwrappedBuffer = null;

    private int remaining = 0;

    private boolean handShakeDone = false;

    private final SocketClient socketClient;
    private final BufferProvider bufferProvider;
    private static final SSLContext SSL_CONTEXT;

    static {
        try {
            SSL_CONTEXT = SSLContext.getInstance("TLS");
            final KeyStore ks = KeyStore.getInstance("JKS");
            final KeyStore ts = KeyStore.getInstance("JKS");

            final char[] passphrase = System.getProperty("javax.net.ssl.trustStorePassword").toCharArray();

            final String keyStoreFile = System.getProperty("javax.net.ssl.trustStore");
            ks.load(new FileInputStream(keyStoreFile), passphrase);
            ts.load(new FileInputStream(keyStoreFile), passphrase);

            final KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, passphrase);

            final TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
            tmf.init(ts);

            SSL_CONTEXT.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public SSLHandler(final SocketClient socketClient, final BufferProvider bufferProvider, final SocketChannel channel) {
        this.socketClient = socketClient;
        this.bufferProvider = bufferProvider;
        this.channel = channel;

        sslEngine = SSL_CONTEXT.createSSLEngine(socketClient.getServerAddress().getHost(), socketClient.getServerAddress().getPort());
        sslEngine.setUseClientMode(true);

        this.sendBuffer = ByteBuffer.allocate(sslEngine.getSession().getPacketBufferSize());
        this.receiveBuffer = ByteBuffer.allocate(sslEngine.getSession().getPacketBufferSize());
        this.unwrappedBuffer = ByteBuffer.allocate(sslEngine.getSession().getApplicationBufferSize());

        try {
            sslEngine.beginHandshake();
        } catch (SSLException e) {
            throw new MongoSocketOpenException(e.getMessage(), socketClient.getServerAddress(), e);
        }
    }

    public void stop() throws IOException {
        sslEngine.closeOutbound();
    }

    protected int doWrite(final ByteBuffer buff) throws IOException {
        doHandshake();
        final int out = buff.remaining();
        while (buff.remaining() > 0) {
            if (wrapAndWrite(buff) < 0) {
                return -1;
            }
        }
        return out;
    }

    protected int doRead(final ByteBuf buff) throws IOException {

        if (readAndUnwrap(buff) >= 0) {
            doHandshake();
        } else {
            return -1;
        }

        return buff.position();
    }

    private int wrapAndWrite(final ByteBuffer buff) {
        try {
            Status status;
            sendBuffer.clear();
            do {
                status = sslEngine.wrap(buff, sendBuffer).getStatus();
                if (status == Status.BUFFER_OVERFLOW) {
                    // There data in the net buffer therefore need to send out the data
                    flush();
                }
            } while (status == Status.BUFFER_OVERFLOW);
            if (status == Status.CLOSED) {
                throw new MongoSocketWriteException("SSLEngine Closed", socketClient.getServerAddress());
            }
            return flush();
        } catch (IOException e) {
            throw new MongoSocketWriteException(e.getMessage(), socketClient.getServerAddress(), e);
        }
    }

    private int flush() throws IOException {
        sendBuffer.flip();
        int count = 0;
        while (sendBuffer.hasRemaining()) {
            final int x = channel.write(sendBuffer);
            if (x >= 0) {
                count += x;
            } else {
                count = x;
            }
        }
        sendBuffer.compact();
        return count;
    }

    private int readAndUnwrap(final ByteBuf buff) {
        if (!channel.isOpen()) {
            throw new MongoSocketReadException("Channel is closed", socketClient.getServerAddress());
        }
        boolean needRead;

        if (remaining > 0) {
            receiveBuffer.compact();
            receiveBuffer.flip();
            needRead = false;
        } else {
            receiveBuffer.clear();
            needRead = true;
        }

        int x = 0;
        try {
            Status status;
            do {
                if (needRead) {
                    x = channel.read(receiveBuffer);
                    if (x == -1) {
                        return -1;
                    }
                    receiveBuffer.flip();

                }
                if (handShakeDone && remaining(unwrappedBuffer) > 0) {
                    status = Status.OK;
                } else {
                    status = sslEngine.unwrap(receiveBuffer, unwrappedBuffer).getStatus();
                    if (handShakeDone) {
                        unwrappedBuffer.flip();
                    }
                }

                if (x == 0 && receiveBuffer.position() == 0) {
                    receiveBuffer.clear();
                }
                if (x == 0 && handShakeDone) {
                    return 0;
                }
                if (status == Status.BUFFER_UNDERFLOW) {
                    needRead = true;
                } else if (status == Status.BUFFER_OVERFLOW) {
                    unwrappedBuffer = ByteBuffer.allocate(receiveBuffer.limit() * 2);
                } else if (status == Status.CLOSED) {
                    buff.flip();
                    return -1;
                }
                try {
                    if (handShakeDone) {
                        final byte[] array = new byte[buff.limit()];
                        unwrappedBuffer.get(array);
                        buff.asNIO().put(array);
                    }
                } catch (BufferUnderflowException e) {
                    needRead = true;
                }
            } while (status != Status.OK);
            if (unwrappedBuffer.capacity() > sslEngine.getSession().getApplicationBufferSize()) {
                unwrappedBuffer = ByteBuffer.allocate(sslEngine.getSession().getApplicationBufferSize());
            }
        } catch (IOException e) {
            throw new MongoSocketReadException(e.getMessage(), socketClient.getServerAddress(), e);
        }

        remaining = receiveBuffer.remaining();
        return buff.position();
    }

    private int remaining(final ByteBuffer buffer) {
        return buffer.limit() - buffer.position();
    }

    private void doHandshake() {

        handShakeDone = false;
        final ByteBuf tmpBuff = bufferProvider.get(sslEngine.getSession().getApplicationBufferSize());
        HandshakeStatus status = sslEngine.getHandshakeStatus();
        while (status != HandshakeStatus.FINISHED && status != HandshakeStatus.NOT_HANDSHAKING) {
            switch (status) {
                case NEED_TASK:

                    final Executor exec = Executors.newSingleThreadExecutor();
                    Runnable task;

                    while ((task = sslEngine.getDelegatedTask()) != null) {
                        exec.execute(task);
                    }
                    break;
                case NEED_WRAP:
                    tmpBuff.clear();
                    tmpBuff.flip();
                    if (wrapAndWrite(tmpBuff.asNIO()) < 0) {
                        throw new MongoSocketOpenException("SSLHandshake failed", socketClient.getServerAddress());
                    }
                    break;

                case NEED_UNWRAP:
                    tmpBuff.clear();
                    if (readAndUnwrap(tmpBuff) < 0) {
                        throw new MongoSocketOpenException("SSLHandshake failed", socketClient.getServerAddress());
                    }
                    break;
                default:
            }
            status = sslEngine.getHandshakeStatus();
        }
        handShakeDone = true;
    }
}