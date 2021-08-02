### Content:
1. Performance
2. Strace Analysis
3. My Observation & Problem Analysis 
4. Implementation to achieve better parallelization
(Not specified in spec, but beneficial for understanding my program)

### 1. Performance:
(The order is gzip, pigz, Pigzj)

(1) Default
```
real	0m7.921s
user	0m7.469s
sys	0m0.055s

real	0m2.267s
user	0m7.369s
sys	0m0.041s

real	0m2.612s
user	0m7.203s
sys	0m0.227s
-rw-r--r-- 1 classque class 43261332 May  4 10:52 gzip.gz
-rw-r--r-- 1 classque class 43134815 May  4 10:52 pigz.gz
-rw-r--r-- 1 classque class 44047588 May  4 10:52 Pigzj.gz
```

(2) Two threads
```
real	0m7.937s
user	0m7.459s
sys	0m0.068s

real	0m4.080s
user	0m7.347s
sys	0m0.076s

real	0m4.129s
user	0m7.170s
sys	0m0.211s
-rw-r--r-- 1 classque class 43261332 May  4 10:58 gzip.gz
-rw-r--r-- 1 classque class 43134815 May  4 10:58 pigz.gz
-rw-r--r-- 1 classque class 44047588 May  4 10:59 Pigzj.gz
```

(3) One thread
```
real	0m7.751s
user	0m7.508s
sys	0m0.050s

real	0m7.733s
user	0m7.238s
sys	0m0.068s

real	0m7.603s
user	0m7.073s
sys	0m0.315s
-rw-r--r-- 1 classque class 43261332 May  4 11:00 gzip.gz
-rw-r--r-- 1 classque class 43134815 May  4 11:00 pigz.gz
-rw-r--r-- 1 classque class 44047588 May  4 11:00 Pigzj.gz
```

(4) Three threads
```
real	0m7.947s
user	0m7.459s
sys	0m0.057s

real	0m2.882s
user	0m7.323s
sys	0m0.091s

real	0m3.024s
user	0m7.125s
sys	0m0.281s
-rw-r--r-- 1 classque class 43261332 May  4 11:02 gzip.gz
-rw-r--r-- 1 classque class 43134815 May  4 11:02 pigz.gz
-rw-r--r-- 1 classque class 44047588 May  4 11:02 Pigzj.gz
```
(5) Compression Ratios (compressed / original)
gzip: 0.34349
pigz: 0.34249
Pigzj: 0.34974

My implementation results in a larger compressed file. 
However, it is not noticeable since all three implementation 
varie less than 1%.

### 2. Strace Analysis:
The usage of multithreading makes pigz and Pigzj runs faster. 
However, Pigzj needs to use jdk and check a few programs 
which result in a longer time of execution.

By testing through a smaller test.txt file, I notice that 
my java implementation always need to call openat() at first 
to check and open programs for runtime. 
However, most of them don't exist. Namely, 
(traces are shrinked to satisfy column length requirement)

openat(AT_FDCWD, "/usr/local/cs/jdk-16.0.1/bin/tls/haswell/avx512_1/
x86_64/libz.so.1", O_RDONLY|O_CLOEXEC) = -1 ENOENT 
(No such file or directory)

stat("/usr/local/cs/jdk-16.0.1/bin/tls/haswell/avx512_1/x86_64", 
0x7ffedf3610f0) = -1 ENOENT (No such file or directory)

openat(AT_FDCWD, "/usr/local/cs/jdk-16.0.1/bin/tls/haswell/avx512_1
/libz.so.1", O_RDONLY|O_CLOEXEC) = -1 ENOENT (No such file or directory)

stat("/usr/local/cs/jdk-16.0.1/bin/tls/haswell/avx512_1", 
0x7ffedf3610f0) = -1 ENOENT (No such file or directory)

openat(AT_FDCWD, "/usr/local/cs/jdk-16.0.1/bin/tls/haswell
/x86_64/libz.so.1", 
O_RDONLY|O_CLOEXEC) = -1 ENOENT (No such file or directory)

stat("/usr/local/cs/jdk-16.0.1/bin/tls/haswell/x86_64", 
0x7ffedf3610f0) = -1 ENOENT (No such file or directory)
...


In the middle of execution, openat() are also invoked a few times 
with tools in jdk to support system calls like read and mmap. 
These tools might differ in performance 
with implementation in C (a little slower).

Both Pigzj and pigz evokes system calls that are related to 
multithreading. 

But Pigzj again needs to check a few programs 
before using the default threading tool. 

Namely, 

openat(AT_FDCWD, "/usr/local/cs/jdk-16.0.1/bin/libpthread.so.0", 
O_RDONLY|O_CLOEXEC) = -1 ENOENT (No such file or directory)

openat(AT_FDCWD, "/usr/local/cs/jdk-16.0.1/bin/../lib/libpthread.so.0", 
O_RDONLY|O_CLOEXEC) = -1 ENOENT (No such file or directory)

openat(AT_FDCWD, "/lib64/libpthread.so.0", O_RDONLY|O_CLOEXEC) = 3

In terms of system calls, Both Pigzj and pigz needs to use 
clone() and futex() to support multithreading 
and ensure data integrity. These are system calls that gzip 
doesn't use and make pigz and Pigzj run faster 
when dealing with large files.

### 3. Observation & Problem Analysis
(1) Observation: 
a. scalability issue
When threads scale up, there might be cases where many threads are not
in use. For example, 10 chunks and three threads a,b,c, but the first chunk
is really hard to compress. Then, after b and c finish compressing, a is 
still compressing so we are still waiting for a and not starting b and c.
This case would happen more often if there are more chunks and more threads.

Like I mentioned below, dispatching all available threads and sort the result
based on the sequence number might be fitting if this case is happening often.
However, sorting also takes time when there are many chunks and many threads.

b. speed issue
Java is slower than C. By looking at the time difference between Pigzj and 
pigz, we could imagine Pigzj will be much slower when the file to compress
is extremely large. By checking the strace of Pigzj and pigz, we notice
Pigzj evokes a really similar set of system calls to pigz's. Java's
implemenation introduces some unnesscary overheads.

(2) Problems:
a. empty file: 
for some reason, pigz would produce random 
binary data when compressing empty stdin.
However, my implemenation would ignore empty stdin since there 
is no way to forsee the stdin to be empty. 
Some algorithm to handle the empty stdin must be introduced in 
gzip and pigz  that cannot be trivially introduced in this java program.

4. Implementation to achieve better parallelization:
This section is not required in the spec, however, I notice in Piazza TAs say 
they would focus more on parallelization and performance. So, I drafted a
section to explain my stragies to boost performance.

(1) Multithreading
According to the spec:
    "(Pigz) divide the input into fixed-size blocks (with block 
    size equal to 128 KiB), and have P threads that are each busily 
    compressing a block. That is, pigz starts by reading P blocks and 
    starting a compression thread on each block. It then waits for the 
    first thread to finish, outputs its result, and then can reuse that 
    thread to compress the (P+1)st block."

According to the comment in pigz.c:
    "When doing parallel compression, pigz uses the main thread 
    to read the input in 'size' sized chunks (see -b), and puts those in a 
    compression job list, each with a sequence number 
    to keep track of the ordering."
    If it is not the first chunk, then that job also points to 
    the previous input buffer, from which the last 32K will be used 
    as a dictionary (unless -i is specified).
    This sets a lower limit of 32K on 'size'."


What I did was implementing what said in these two sentences exactly.

a. Divide input into sized chunk:
    I create a constant CHUNK_SIZE = 131072 which specifies the sized chunk 
    to be 128 KiB. Then,I read input from System.in 
    into a buffer with CHUNK_SIZE. 
    However, instead of creating a compression job list, I created a java class 
    ``MyThread`` which has a ``chunk`` field storing 
    its corresponding job, and then I store all ``MyThread`` 
    into an array ``myThreads`` in order
    to conveniently start and join threads. 
    Instead, my implementation first dispatches all threads to 
    corresponding comprssion tasks. Then, it waits for the first thread 
    to join, dispatch first thread again, and waits for the second
    thread to join (so on so forth).
    e.g. If there are 10 chunks and 3 threads a,b,c, first, a,b,c are 
    all dispatched to compress chunks. Then, my program would wait for a 
    to finish, output a's result, dispatch a to compress the 4th chunk, 
    and then wait for b to finish to let it compress 5th chunk etc.
    Notice, there is another case. When stdin is small, some threads 
    might not be used. For instance, if we have 2 chunks and 3 threads, 
    only a, b would be used and c might not be used. In this case,
    we should wait for a first, and b second to produce the meaningful 
    compression result. I create
    a ``rotate`` flag and serveral helper functions to handle 
    different join() cases.  

b. Write Output
    I changed the implemenation 
    of the compress() in the hint code to return an outStream instead of void. 
    Then, I store the outStream into a field called ``output`` 
    in ``MyThread``.
    After I joined any thread i, I would call 
    ``myThreads[i].output.writeTo(System.out)`` 
    to write the result to the stdout.

c. Dictionary
    According to pigz's comment and the spec, when compressing each chunk of 
    data, if it is not the first chunk, it needs to use the last chunk's 
    last 32KiB as the dictionary. I implementted this by introducing a field
    ``prev_chunk`` in my ``MyThread`` Object. The ``prev_chunk`` is a reference
    that refers to the chunk that we've read previously. 

d. Parallelism
    The Parallelism is achieved when multiple threads are processing their 
    corresponding chunks. Even though there might be case when we are waiting
    for a and b is finished, achieving this extent of Parallelism corresponds
    to the requirement of the spec and is already much faster than the 
    single-threadedimplemenation.

e. Others
    For consistency with pigz's jobs' sequence numbers, 
    I initialized a field called ``serial`` for ``MyThread``. However,
    ``serial`` is not necessary in my implemenation but it has the potential 
    to optimize my  program if I want to implement a version in which 
    I would dispatch any available threads instead of wait for 
    next thread and sort the compressed results in the end 
    based on ``serial``. However, 
    this doesn't follow the instruction on the spec.

(2) Atomic data for each thread
    To boost the effciency of multithreading, my implemenation doesn't involve
    using mutex locks, synchonized functions, or monitors. For each MyThread 
    object, I made sure that the chunk and prev_chunk are unique for this 
    thread and chunk and prev_chunk are created in the single main thread
    without modification before start() and join(). This guarantees that each
    thread is given atomic data for compression so there cannot be a data race
    because there is no shared global structures.
    Then, when collecting the result, I joined the thread first then output
    the result, so the output is in fact handled by the single main thread
    and no potential data race could be introduced.
    Contrast to approach where a global data structure is shared by all 
    threads, this approach introduces less overhead by not introducing 
    synchonized actions or any locks.

(3) Compression
    I created a class ``SingleCompressor`` in ``MyThread``. This class is a 
    modified version to the hint code given by TAs. 
    I modified the finish condition for each compression to
    tackle the "crc mismatch" problem. And the ``SingleCompressor`` is given 
    chunk to compress and prev_chunk to use as dictionary so that 
    a while loop is no longer needed.
    By not using a while loop in SingleCompressor and assign a compressor
    to each thread, we not only ensured the compress() action is integrate
    but also speeding up the process since 
