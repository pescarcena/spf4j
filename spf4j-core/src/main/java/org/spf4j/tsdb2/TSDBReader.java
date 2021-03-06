 /*
 * Copyright (c) 2001, Zoltan Farkas All Rights Reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.spf4j.tsdb2;

import com.sun.nio.file.SensitivityWatchEventModifier;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.apache.avro.Schema;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.spf4j.base.Either;
import org.spf4j.base.Handler;
import org.spf4j.concurrent.DefaultExecutor;
import org.spf4j.io.MemorizingBufferedInputStream;
import org.spf4j.tsdb2.avro.DataBlock;
import org.spf4j.tsdb2.avro.Header;
import org.spf4j.tsdb2.avro.TableDef;

/**
 *
 * @author zoly
 */
@SuppressFBWarnings("IICU_INCORRECT_INTERNAL_CLASS_USE")
public final  class TSDBReader implements Closeable {

    private final MemorizingBufferedInputStream bis;
    private final Header header;
    private long size;
    private final BinaryDecoder decoder;
    private final SpecificDatumReader<Object> recordReader;
    private RandomAccessFile raf;
    private final File file;

    public TSDBReader(final File file, final int bufferSize) throws IOException {
        this.file = file;
        final FileInputStream fis = new FileInputStream(file);
        bis = new MemorizingBufferedInputStream(fis);
        SpecificDatumReader<Header> reader = new SpecificDatumReader<>(Header.getClassSchema());
        decoder = DecoderFactory.get().directBinaryDecoder(bis, null);
        TSDBWriter.validateType(bis);
        DataInputStream dis = new DataInputStream(bis);
        size = dis.readLong();
        header = reader.read(null, decoder);
        recordReader = new SpecificDatumReader<>(
                new Schema.Parser().parse(header.getContentSchema()),
                Schema.createUnion(Arrays.asList(TableDef.SCHEMA$, DataBlock.SCHEMA$)));
    }


    /**
     * method useful when implementing tailing.
     * @return true if size changed.
     * @throws IOException
     */
    public synchronized boolean reReadSize() throws IOException {
        if (raf == null) {
            raf = new RandomAccessFile(file, "r");
        }
        raf.seek(TSDBWriter.MAGIC.length);
        long old = size;
        size = raf.readLong();
        return size != old;
    }


    @Nullable
    public synchronized Either<TableDef, DataBlock> read() throws IOException {
        final long position = bis.getReadBytes();
        if (position >= size) {
            return null;
        }
        Object result = recordReader.read(null, decoder);
        if (result instanceof TableDef) {
            final TableDef td = (TableDef) result;
            if (position != td.id) {
                throw new IOException("Table Id should be equal with file position " + position + ", " + td.id);
            }
            return Either.left(td);
        } else {
            return Either.right((DataBlock) result);
        }
    }


    @Override
    public synchronized void close() throws IOException {
        try (InputStream is = bis) {
            if (raf != null) {
                raf.close();
            }
        }
    }

    public synchronized long getSize() {
        return size;
    }

    public Header getHeader() {
        return header;
    }

    private volatile boolean watch;

    public void stopWatching() {
        watch =  false;
    }


    //CHECKSTYLE:OFF
    public synchronized <E extends Exception>  Future<Void> bgWatch(
            final Handler<Either<TableDef, DataBlock>, E> handler,
            final EventSensitivity es) {
        //CHECKSTYLE:ON
        return DefaultExecutor.INSTANCE.submit(new Callable<Void>() {

            @Override
            public Void call() throws Exception {
                watch(handler, es);
                return null;
            }
        });
    }


    public enum EventSensitivity {
        HIGH, MEDIUM, LOW
    }


    //CHECKSTYLE:OFF
    public synchronized <E extends Exception>  void watch(final Handler<Either<TableDef, DataBlock>, E> handler,
            final EventSensitivity es)
            throws IOException, InterruptedException, E {
        //CHECKSTYLE:ON
        if (watch) {
            throw new IllegalStateException("File is already watched " + file);
        }
        SensitivityWatchEventModifier sensitivity;
        switch (es) {
            case LOW:
                sensitivity = SensitivityWatchEventModifier.LOW;
                break;
            case MEDIUM:
                sensitivity = SensitivityWatchEventModifier.MEDIUM;
                break;
            case HIGH:
                sensitivity = SensitivityWatchEventModifier.HIGH;
                break;
            default:
                throw new UnsupportedOperationException("Unsupported sensitivity " + es);
        }

        watch = true;
        read(handler);
        final Path path = file.getParentFile().toPath();
        try (WatchService watchService = path.getFileSystem().newWatchService()) {
            path.register(watchService, new WatchEvent.Kind[] {StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.OVERFLOW
            }, sensitivity);
            do {
                WatchKey key = watchService.poll(1000, TimeUnit.MILLISECONDS);
                if (key == null) {
                    continue;
                }
                if (!key.isValid()) {
                    key.cancel();
                    break;
                }
                if (!key.pollEvents().isEmpty()) {
                    if (reReadSize()) {
                        read(handler);
                    }
                }
                if (!key.reset()) {
                    key.cancel();
                    break;
                }
            } while (watch);
        } finally {
            watch = false;
        }
    }

    //CHECKSTYLE:OFF
    public synchronized <E extends Exception> void read(final Handler<Either<TableDef, DataBlock>, E> handler)
            throws IOException, E {
        //CHECKSTYLE:ON
        Either<TableDef, DataBlock> data;
        while ((data = read()) != null) {
            handler.handle(data, Long.MAX_VALUE);
        }
    }

    @Override
    public String toString() {
        return "TSDBReader{" + "size=" + size + ", raf=" + raf + ", file=" + file + '}';
    }

}
