import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.Deflater;


class MyThread extends Thread {
    private int s; // index in the job list
    private byte[] pB; // reference to previous buffer
    private byte[] cB; // reference to current buffer
    private int nBytes; // size of the current buffer
    private int p_nBytes; // size of the previous buffer
    private boolean hasDict; // only the first chunk doesn't have dict
    public ByteArrayOutputStream output; // Output stream to store compressed output

    class SingleCompress {
        public final static int BLOCK_SIZE = 131072; // 128 KB
        public final static int DICT_SIZE = 32768; // 32 KB
        private final static int GZIP_MAGIC = 0x8b1f;
        private final static int TRAILER_SIZE = 8;

        private byte[] pB;
        private byte[] cB;
        private boolean hasDict;
        private int nBytes;
        private int p_nBytes;

        public ByteArrayOutputStream outStream;

        public SingleCompress(byte[] pB, byte[] cB, boolean hasDict, int nBytes, int p_nBytes) {
            this.outStream = new ByteArrayOutputStream();
            this.pB = pB;
            this.cB = cB;
            this.hasDict = hasDict;
            this.nBytes = nBytes;
            this.p_nBytes = p_nBytes;
        }

        /*
         *  Get dictBuf from previous buffer
         * */
        private void GetDictBuf(byte[] dB, int nBytes) {
            System.arraycopy(pB, nBytes - DICT_SIZE, dB, 0, DICT_SIZE);
        }

        private void writeHeader() throws IOException {
            outStream.write(new byte[]{
                    (byte) GZIP_MAGIC,        // Magic number (short)
                    (byte) (GZIP_MAGIC >> 8),  // Magic number (short)
                    Deflater.DEFLATED,        // Compression method (CM)
                    0,                        // Flags (FLG)
                    0,                        // Modification time MTIME (int)
                    0,                        // Modification time MTIME (int)
                    0,                        // Modification time MTIME (int)
                    0,                        // Modification time MTIME (int)Sfil
                    0,                        // Extra flags (XFLG)
                    0                         // Operating system (OS)
            });
        }

        /*
         * Writes GZIP member trailer to a byte array, starting at a given
         * offset.
         */
        private void writeTrailer(long totalBytes, byte[] buf, int offset, CRC32 crc) throws IOException {
            writeInt((int) crc.getValue(), buf, offset); // CRC-32 of uncompr. data
            writeInt((int) totalBytes, buf, offset + 4); // Number of uncompr. bytes
        }

        /*
         * Writes integer in Intel byte order to a byte array, starting at a
         * given offset.
         */
        private void writeInt(int i, byte[] buf, int offset) throws IOException {
            writeShort(i & 0xffff, buf, offset);
            writeShort((i >> 16) & 0xffff, buf, offset + 2);
        }

        /*
         * Writes short integer in Intel byte order to a byte array, starting
         * at a given offset
         */
        private void writeShort(int s, byte[] buf, int offset) throws IOException {
            buf[offset] = (byte) (s & 0xff);
            buf[offset + 1] = (byte) ((s >> 8) & 0xff);
        }

        public ByteArrayOutputStream compress() throws IOException {
            this.writeHeader();

            /* Buffers for input blocks, compressed bocks, and dictionaries */
            byte[] cmpBlockBuf = new byte[BLOCK_SIZE * 2];
            byte[] dictBuf = new byte[DICT_SIZE];


            if (hasDict) GetDictBuf(dictBuf, p_nBytes);

            Deflater compressor = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
            CRC32 crc = new CRC32();
            crc.update(cB, 0, nBytes);
            compressor.reset();

            /* If we saved a dictionary from the last block, prime the deflater with it */
            if (hasDict) {
                compressor.setDictionary(dictBuf);
            }

            compressor.setInput(cB, 0, nBytes);


            if (!compressor.finished()) {
                compressor.finish();
                while (!compressor.finished()) {
                    int deflatedBytes = compressor.deflate(cmpBlockBuf, 0, cmpBlockBuf.length, Deflater.SYNC_FLUSH);
                    if (deflatedBytes > 0) {
                        outStream.write(cmpBlockBuf, 0, deflatedBytes);
                    }
                }
            }


            /* Finally, write the trailer and then write to STDOUT */
            byte[] trailerBuf = new byte[TRAILER_SIZE];
            writeTrailer(nBytes, trailerBuf, 0, crc);
            outStream.write(trailerBuf);
            return outStream;
        }
    }

    public MyThread(int serial, byte[] prevBlock, byte[] curBlock, int nBytes, int p_nBytes) {
        s = serial;
        pB = prevBlock;
        cB = curBlock;
        this.nBytes = nBytes;
        this.p_nBytes = p_nBytes;
    }

    @Override
    public void run() {
        SingleCompress sc = new SingleCompress(pB, cB, hasDict, nBytes, p_nBytes);
        try {
            output = sc.compress();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

public class Pigzj {
    public final static int CHUNK_SIZE = 131072; // 131072
    public static int processes = Runtime.getRuntime().availableProcessors();

    /*
     *  Get the total number of processors
     * */
    private static void getProcesses(String args[]) {
        boolean p = false;
        for (String arg : args) {
            if (p) {
                try {
                    int a = Integer.parseInt(arg);
                    if (a > processes)
                        handleException(new Exception("invalid -p options" + "a: " + a + "p: " + processes));
                    processes = a;
                    p = false;
                } catch (Exception e) {
                    handleException(e);
                }
            } else {
                if (arg.equals("-p"))
                    p = true;
                else
                    handleException(new Exception("invalid option: " + arg));
            }

        }
    }

    private static void joinThreads(int next_join, MyThread[] myThreads, int to) throws InterruptedException, IOException {
        int p = next_join;
        if (to == -1) { // if to < -1, i.e. next_join = 0, set it to largest index
            to = processes - 1;
        }
        while (true) {
            try {
                myThreads[p].join();
                myThreads[p].output.writeTo(System.out);
                if (p == to) break; // joined every thread between next_join and to (excluded)

                if (p < processes - 1) {
                    p++;
                } else {
                    p = 0;
                }

            } catch (Exception e) {
                handleException(e);
            }

        }
    }

    private static int joinSingle(MyThread[] myThreads, int next_join, int i,
                                  byte[] prev_chunk, byte[] chunk,
                                  int nBytes, int p_size) throws InterruptedException, IOException {
        myThreads[next_join].join();
        myThreads[next_join].output.writeTo(System.out);
        myThreads[next_join] = new MyThread(i, prev_chunk, chunk, nBytes, p_size);
        myThreads[next_join].start();
        return next_join + 1;
    }

    public static void handleException(Exception e) {
        System.err.println(e.getMessage());
        System.exit(2);
    }

    public static void main(String args[]) throws Exception {
        getProcesses(args); // Get how many processors available
        MyThread[] myThreads = new MyThread[processes];
        byte[] prev_chunk = new byte[CHUNK_SIZE];
        int p_size = -1;

        int next_join = 0;
        boolean rotate = false;
        int i = 0;

        try {
            while (true) {
                if (System.out.checkError()) {
                    handleException(new Exception("checkError - Found error"));
                }
        
                byte[] chunk = new byte[CHUNK_SIZE];
                int nBytes;
                if ((nBytes = System.in.read(chunk)) < 0) { // Nothing to be read, joins all threads
                    if (!rotate) {
                        if (i == 0) break; // empty file, empty result
                        joinThreads(0, myThreads, i - 1);
                    } else {
                        joinThreads(next_join, myThreads, next_join - 1);
                    }
                    break; // everything is joined
                } else { // still are things to be read
                    if (!rotate) {
                        if (i == 0)
                            myThreads[i] = new MyThread(i, new byte[0], chunk, nBytes, 0); // first chunk, no prev_Block
                        else
                            myThreads[i] = new MyThread(i, prev_chunk, chunk, nBytes, p_size);
                        myThreads[i].start();
                    } else { // if rotate
                        next_join = joinSingle(myThreads, next_join, i, prev_chunk, chunk, nBytes, p_size);
                        if (next_join == processes) {
                            next_join = 0;
                        }
                    }
                }
                if (i == processes - 1) {
                    rotate = true;
                }
                i++;
            }
        } catch (Exception e) {
            handleException(e);
        }
    }
}
