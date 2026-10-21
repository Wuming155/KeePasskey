package com.keepasskey.app.data.repository;

import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.OsConstants;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 649 合成读取故障；门有超时且必须显式恢复，不承诺中断永不返回的提供方。 */
final class SafKeyReadProbe {
    private static final int CHUNK_BYTES = 8192;
    private static final long GATE_TIMEOUT_SECONDS = 30;
    private final File file;
    private volatile CountDownLatch release = new CountDownLatch(1);
    private volatile boolean entered;
    private volatile boolean closed;
    private volatile long readBytes;

    SafKeyReadProbe(File file) { this.file = file; }

    void seed(int size) {
        byte[] chunk = new byte[CHUNK_BYTES];
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            output.setLength(0);
            int offset = 0;
            while (offset < size) {
                int count = Math.min(chunk.length, size - offset);
                for (int index = 0; index < count; index++) chunk[index] = (byte) ((offset + index) % 251);
                output.write(chunk, 0, count);
                offset += count;
            }
        } catch (IOException error) { throw new IllegalStateException(error); }
        finally { java.util.Arrays.fill(chunk, (byte) 0); }
    }

    Object reportedSize(String fault) {
        if ("KEY_UNKNOWN".equals(fault)) return null;
        if ("KEY_SIZE_SMALL".equals(fault)) return 1L;
        if ("KEY_SIZE_LARGE".equals(fault)) return Long.MAX_VALUE;
        return file.length();
    }

    void release() { release.countDown(); }

    Bundle state() {
        Bundle state = new Bundle();
        state.putBoolean("readEntered", entered);
        state.putBoolean("readClosed", closed);
        state.putLong("readBytes", readBytes);
        return state;
    }

    ParcelFileDescriptor open(String fault, StorageManager storage, Handler handler) throws FileNotFoundException {
        if ("KEY_DENIED".equals(fault)) throw new SecurityException("injected key read denial");
        entered = false;
        closed = false;
        readBytes = 0;
        release = new CountDownLatch(1);
        if ("KEY_UNKNOWN".equals(fault)) return unknownPipe();
        try {
            return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,
                new ProxyFileDescriptorCallback() {
                    @Override public long onGetSize() { return file.length(); }
                    @Override public int onRead(long offset, int size, byte[] data) throws ErrnoException {
                        entered = true;
                        if ("KEY_SLOW".equals(fault)) awaitRelease();
                        if ("KEY_PARTIAL_ERROR".equals(fault) && offset >= CHUNK_BYTES) {
                            throw new ErrnoException("read", OsConstants.EIO);
                        }
                        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                            input.seek(offset);
                            int count = Math.max(0, input.read(data, 0, Math.min(size, CHUNK_BYTES)));
                            readBytes += count;
                            return count;
                        } catch (IOException error) { throw new ErrnoException("read", OsConstants.EIO, error); }
                    }
                    @Override public void onRelease() { closed = true; }
                }, handler);
        } catch (IOException error) {
            FileNotFoundException wrapped = new FileNotFoundException("key read proxy unavailable");
            wrapped.initCause(error);
            throw wrapped;
        }
    }

    private void awaitRelease() throws ErrnoException {
        try {
            if (!release.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new ErrnoException("read gate timeout", OsConstants.ETIMEDOUT);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ErrnoException("read gate interrupted", OsConstants.EINTR, error);
        }
    }

    private ParcelFileDescriptor unknownPipe() throws FileNotFoundException {
        final ParcelFileDescriptor[] pipe;
        try { pipe = ParcelFileDescriptor.createReliablePipe(); }
        catch (IOException error) { throw new FileNotFoundException("key pipe unavailable"); }
        new Thread(() -> {
            byte[] chunk = new byte[CHUNK_BYTES];
            try (RandomAccessFile input = new RandomAccessFile(file, "r");
                 ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                entered = true;
                int count;
                while ((count = input.read(chunk)) >= 0) {
                    output.write(chunk, 0, count);
                    readBytes += count;
                }
            } catch (IOException error) {
                try { pipe[1].closeWithError("injected key pipe failure"); }
                catch (IOException closeError) { error.addSuppressed(closeError); }
            } finally { java.util.Arrays.fill(chunk, (byte) 0); closed = true; }
        }, "saf649-key-pipe").start();
        return pipe[0];
    }
}
