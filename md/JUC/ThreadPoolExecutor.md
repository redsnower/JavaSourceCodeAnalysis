## ThreadPoolExecutor

### 继承结构及完整源码解析

[Executor](https://github.com/Augustvic/JavaSourceCodeAnalysis/blob/master/src/JUC/Executor.java) | [ExecutorService](https://github.com/Augustvic/JavaSourceCodeAnalysis/blob/master/src/JUC/ExecutorService.java) | [AbstractExecutorService](https://github.com/Augustvic/JavaSourceCodeAnalysis/blob/master/src/JUC/AbstractExecutorService.java) | [ThreadPoolExecutor](https://github.com/Augustvic/JavaSourceCodeAnalysis/blob/master/src/JUC/ThreadPoolExecutor.java)

<img src="https://github.com/Augustvic/JavaSourceCodeAnalysis/blob/master/images/ThreadPoolExecutor.png" width=70% />

### 线程池状态

ThreadPoolExecutor 线程池有 RUNNING, SHUTDOWN, STOP, TIDYING, TERMINATED 五种状态，状态之间的关系如下图所示（引用自[JUC源码分析-线程池篇（一）：ThreadPoolExecutor](https://www.jianshu.com/p/7be43712ef21)）：

<img src="https://github.com/Augustvic/JavaSourceCodeAnalysis/blob/master/images/ThreadPoolExecutor2.png" width=70% />

RUNNING：正常运行状态，接受新的任务（如果没有达到拒绝策略的条件）

SHUTDOWN：不接收新的任务，但是会继续运行正在线程中执行的任务和在队列中等待的任务

STOP：不接收新任务，也不会运行队列任务，并且中断正在运行的任务

TIDYING：所有任务都已经终止，workerCount 为0，当池状态为TIDYING时将会运行terminated()方法

TERMINATED：完全终止

线程池状态保存在作为类属性的原子整型变量 ctl 的高 3 位 bits 中。

### 添加新任务

常用的线程池添加一个新任务时，主要有以下步骤：

1. 若当前线程数小于核心线程数，创建一个新的线程执行该任务。

2. 若当前线程数大于等于核心线程数，且任务队列未满，将任务放入任务队列，等待空闲线程执行。

3. 若当前线程数大于等于核心线程数，且任务队列已满

    3.1 若线程数小于最大线程数，创建一个新线程执行该任务
    
    3.2 若线程数等于最大线程数，执行拒绝策略

需要注意的两个地方是：当当前线程数达到核心线程数后，把新来的任务放入队列而不是创建新的线程；当线程数达到最大线程数且任务队列已满的时候，才会执行拒绝策略。

### 静态常量

CAPACITY 表示此线程池最大有效线程数为 (2^29)-1，而有效线程数存储在变量 ctl 的低 29 位 bits 中。COUNT_BITS 用于便捷地获取 ctl 的高 3 位和低 29 位。

```java
    private static final int COUNT_BITS = Integer.SIZE - 3;
    // 最大有效线程数为 (2^29)-1
    // CAPACITY 的低 29 位全部为 1，高 3 位为 0
    private static final int CAPACITY   = (1 << COUNT_BITS) - 1;

    // runState is stored in the high-order bits
    //111 + 29 个 0
    private static final int RUNNING    = -1 << COUNT_BITS;
    // 000 + 29 个 0
    private static final int SHUTDOWN   =  0 << COUNT_BITS;
    // 001 + 29 个 0
    private static final int STOP       =  1 << COUNT_BITS;
    // 010 + 29 个 0
    private static final int TIDYING    =  2 << COUNT_BITS;
    // 011 + 29 个 0
    private static final int TERMINATED =  3 << COUNT_BITS;
```

### 类属性

最重要的类属性 ctl 是原子整型变量类型，保存了线程池的两个状态，高 3 位表示线程池的状态，低 29 位表示线程池有效线程数。

workQueue 是用于保存待执行任务的阻塞队列。workers 是保存了所有工作线程的集合，在此线程池中，工作线程并不是 Thread，而是封装了 Thread 的 Worker 内部类。

构造一个 ThreadPoolExecutor 线程池主要用到类属性中以下**六个参数**：

* **corePoolSize**：核心线程数，表示通常情况下线程池中活跃的线程个数；

* **maximumPoolSize**：线程池中可容纳的最大线程数；
 
* **keepAliveTime**：此参数表示在线程数大于 corePoolSize 时，多出来的线程，空闲时间超过多少时会被销毁；
 
* **workQueue**：存放任务的阻塞队列；
 
* **threadFactory**：创建新线程的线程工厂；
 
* **handler**：线程池饱和（任务过多）时的拒绝策略

每个变量的具体含义如下所示：

```java
    // ctl 整型变量共有 32 位，低 29 位保存有效线程数 workCount，使用
    // 高 3 位表示线程池运行状态 runState。
    private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));

    // 封装和解封 ctl 中的两个字段
    // 获取 runState
    private static int runStateOf(int c)     { return c & ~CAPACITY; }
    // 获取 workerCount
    private static int workerCountOf(int c)  { return c & CAPACITY; }
    // 如果 workerCount 和 runState 分别是两个整数，将它们合并到一个变量里
    private static int ctlOf(int rs, int wc) { return rs | wc; }


    /**
     * 用于保存任务并且将任务交给工作线程的队列。不需要让 workQueue.poll 返回 null
     * 和队列为空划等号，仅仅依赖 workQueue.isEmpty 的结果来判断队列是否为
     * 空即可（例如在判断状态是否从 SHUTDOWN 转变到 TIDYING）。
     */
    private final BlockingQueue<Runnable> workQueue;

    /**
     * 访问 worker 集合和相关 bookkeeping 持有的锁。虽然可以使用某种类型
     * 的并发集合，但一般使用锁更好。其中一个原因是，它序列化了
     * interruptIdleWorkers，从而避免了不必要的中断风暴，特别是在 shutdown 期间。
     */
    private final ReentrantLock mainLock = new ReentrantLock();

    /**
     * 包括线程池中所有工作线程的集合。
     * HashSet 本身不是线程安全的集合，只有在持有 mainLock 时才能访问。
     */
    private final HashSet<Worker> workers = new HashSet<Worker>();

    /**
     * 用来支持 awaitTermination 的 condition 队列
     */
    private final Condition termination = mainLock.newCondition();

    /**
     * 最大池容量。只有在持有 mainLock 时才能访问。
     */
    private int largestPoolSize;

    /**
     * 已完成任务的计数器。仅在工作线程终止时更新。只有在持有 mainLock 时才能访问。
     */
    private long completedTaskCount;

    /*
     * 所有的用户控制参数都被声明为 volatile，因此正在进行的操作基于最新的值，
     * 不需要锁定，因为没有内部的不变量依赖于它们的同步改变。
     */

    /**
     * 创建新线程的工厂。所有的线程都是使用这个工厂创建的（通过
     * addWorker 方法）。所有的调用者必须为 addWorker 失败做好准备，
     * 这可能是因为系统或用户的策略限制了线程的数量。即使它不被看成一个
     * 错误，创建线程失败可能会导致新的任务被拒绝或者现有任务留在队列中。
     */
    private volatile ThreadFactory threadFactory;

    /**
     * 线程池饱和或 shutdown 时调用。
     */
    private volatile RejectedExecutionHandler handler;

    /**
     * 等待工作的空闲线程的超时时间。超过 corePoolSize 或 allowCoreThreadTimeOut
     * 时，线程使用。否则，它们将永远等待执行新的任务。
     */
    private volatile long keepAliveTime;

    /**
     * 如果为 false（默认），核心线程即使空闲也保持活动状态。
     * 如果为 true，空闲的核心线程由 keepAliveTime 确定存活时间。
     */
    private volatile boolean allowCoreThreadTimeOut;

    /**
     * 除非设置了 allowCoreThreadTimeOut（在这种情况下，最小值为 0），
     * 否则核心线程池的大小即为线程池中保持活跃的线程数目（不允许超时）。
     */
    private volatile int corePoolSize;

    /**
     * 线程池最多容纳线程数。注意实际的最大值受到 CAPACITY 的限制。
     */
    private volatile int maximumPoolSize;
```

### 内部类 Worker

Worker 的属性包含一个线程，由此线程来执行 Worker 自身的 run 函数。

由于继承了 AbstractQueuedSynchronizer 抽象类，Worker 自身也可作为锁的实现，用于并发控制。

```java
    /**
     * Worker 类主要维护执行任务的线程的中断控制状态，以及其他的
     * bookkeeping 功能。该类扩展了 AQS，以简化获取和释放任务执行时的锁。
     * 这可以防止一些试图唤醒正在等待任务工作线程的中断，而不是防止中断
     * 正在运行的任务。我们实现了一个简单的不可重入独占锁，而不是使用
     * ReentrantLock，因为我们不希望工作线程在调用诸如 setCorePoolSize
     * 之类的线程池控制方法时能重入获取锁。另外，为了在线程真正开始运行
     * 任务之前禁止中断，我们将锁状态初始化为一个负值，并在启动时清除它（在
     * runWorker 中）。
     */
    private final class Worker
            extends AbstractQueuedSynchronizer
            implements Runnable
    {
        /**
         * This class will never be serialized, but we provide a
         * serialVersionUID to suppress a javac warning.
         */
        private static final long serialVersionUID = 6138294804551838833L;

        /** 此 worker 运行的线程，如果创建失败为 null */
        final Thread thread;
        /** 初始执行的任务。可能为 null */
        Runnable firstTask;
        /** 每一个线程的任务计数器 */
        volatile long completedTasks;

        /**
         * 构造函数
         * @param firstTask the first task (null if none)
         */
        Worker(Runnable firstTask) {
            // 设置此 AQS 的状态
            setState(-1); // inhibit interrupts until runWorker
            this.firstTask = firstTask;
            // 创建新的线程，传入 this，也就是执行当前 Worker 的 run 函数
            this.thread = getThreadFactory().newThread(this);
        }

        /** Delegates main run loop to outer runWorker  */
        public void run() {
            runWorker(this);
        }

        // Lock methods
        //
        // 0 表示未锁定状态
        // 1 表示锁定状态

        protected boolean isHeldExclusively() {
            return getState() != 0;
        }

        protected boolean tryAcquire(int unused) {
            if (compareAndSetState(0, 1)) {
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }

        protected boolean tryRelease(int unused) {
            setExclusiveOwnerThread(null);
            setState(0);
            return true;
        }

        public void lock()        { acquire(1); }
        public boolean tryLock()  { return tryAcquire(1); }
        public void unlock()      { release(1); }
        public boolean isLocked() { return isHeldExclusively(); }

        // 如果线程存在，则中断线程
        void interruptIfStarted() {
            Thread t;
            if (getState() >= 0 && (t = thread) != null && !t.isInterrupted()) {
                try {
                    t.interrupt();
                } catch (SecurityException ignore) {
                }
            }
        }
    }
```

### 成员函数

#### execute

**execute**

在将任务添加到线程池的执行流程中，依次执行以下步骤：

1. 首先检查线程数是否达到核心线程数，如果没有，调用 addWorker 尝试添加新的线程执行该任务，添加成功则返回。

2. 线程数超过核心线程数，尝试将任务加入任务队列中（空闲的核心线程会在任务队列上等待）。然后再次检查线程池状态是否是 RUNNING，线程池中是否有线程能够运行此任务。

3. 如果尝试添加任务队列失败，再次尝试添加线程执行此任务，此时如果添加线程失败直接执行拒绝策略。添加的线程和核心线程一样，也会持续从队列中获取任务。如果队列中没有任务，那么在经过 keepAlive 时间后，非核心线程将会被删除。

注意此操作执行过程中多次检查线程池状态，因为此过程中线程池可能被 SHUTDOWN 或发生其它变化。

```java
    /**
     * 在未来的某个时间执行给定的任务。此任务会由一个新的线程或存在于线程池
     * 中的某个线程执行。
     *
     * 如果任务不能提交，要么是因为线程池已经被 shut down，要么是因为
     * 达到了最大容量，由当前的 RejectedExecutionHandler 执行拒绝策略。
     *
     * @param command the task to execute
     * @throws RejectedExecutionException at discretion of
     *         {@code RejectedExecutionHandler}, if the task
     *         cannot be accepted for execution
     * @throws NullPointerException if {@code command} is null
     */
    public void execute(Runnable command) {
        if (command == null)
            throw new NullPointerException();
        /*
         * 按以下三个步骤执行：
         *
         * 1. 如果运行的线程数小于 corePoolSize，尝试创建一个新线程，将给定的
         * 任务作为其第一个执行的任务。调用 addWorker 会自动检查 runState
         * 和 workCount，从而通过返回 false 在不应该添加线程的时候发出错误警报。
         *
         * 2. 如果一个任务可以成功地进入队列，我们仍然需要检查是否应该添加
         * 一个新的线程（从任务入队列到入队完成可能有线程死掉，或者线程池
         * 被关闭）。重新检查线程池状态，如果有必要回滚入队操作。如果没有
         * 线程，则添加一个。
         *
         * 3. 如果任务不能入队，再次尝试增加一个新线程，如果添加失败，意味着
         * 池已关闭或已经饱和，此时执行任务拒绝策略。
         */
        int c = ctl.get();
        if (workerCountOf(c) < corePoolSize) {
            if (addWorker(command, true))
                return;
            c = ctl.get();
        }
        // 线程数超过 corePoolSize
        if (isRunning(c) && workQueue.offer(command)) {
            int recheck = ctl.get();
            if (! isRunning(recheck) && remove(command))
                reject(command);
            else if (workerCountOf(recheck) == 0)
                addWorker(null, false);
        }
        else if (!addWorker(command, false))
            reject(command);
    }
```

上述方法中多次调用了添加新线程的函数 addWorker。执行一个任务无非就是三种情况：直接运行、添加到队列、拒绝。

**addWorker**

在 addWorker 函数中，首先自旋操作，检查是否允许添加线程（是否进入 SHUTDOWN 流程和是否达到线程数限制边界）、检查是否成功修改线程池状态。如果满足添加线程的前提条件，且线程数成功增加，则创建一个新的 Worker，将其添加到线程池中。如果添加失败，调用 addWorkerFailed 回滚操作，移除创建失败的 Worker。

由于 workers 是非线程安全的，所以涉及 workers 中元素更新的代码需要加锁。

如果 Worker 添加成功，调用 Thread.start，开启工作线程。

```java
    /**
     * 检查是否可以根据当前线程池的状态和给定的边界（核心线程数和最大线程数）
     * 添加新的 worker。如果允许添加，创建并启动一个新的 worker，运行
     * firstTask 作为其第一个任务。如果线程池停止或者即将被 shutdown，则
     * 此方法返回 false。如果线程工厂创建线程失败，也返回 false。如果线程
     * 创建失败，要么是由于线程工厂返回 null，要么是异常（特别是 Thread.start()
     * 的 OOM），将干净利落地回滚。
     *
     * @param firstTask the task the new thread should run first (or
     * null if none). Workers are created with an initial first task
     * (in method execute()) to bypass queuing when there are fewer
     * than corePoolSize threads (in which case we always start one),
     * or when the queue is full (in which case we must bypass queue).
     * Initially idle threads are usually created via
     * prestartCoreThread or to replace other dying workers.
     *
     * @param core if true use corePoolSize as bound, else
     * maximumPoolSize. (A boolean indicator is used here rather than a
     * value to ensure reads of fresh values after checking other pool
     * state).
     * @return true if successful
     */
    private boolean addWorker(Runnable firstTask, boolean core) {
        retry:
        for (;;) {
            // 获取状态
            int c = ctl.get();
            int rs = runStateOf(c);

            // Check if queue empty only if necessary.
            /* 这一句话可以转化为
            if ((rs > SHUTDOWN) ||
                 (rs >= SHUTDOWN && firstTask == null) ||
                 (rs >= SHUTDOWN && workQueue.isEmpty()))
             若线程池状态大于 SHUTDOWN 或者
             （状态大于等于 SHUTDOWN 且 firstTask == null）或者
             （状态大于等于 SHUTDOWN 且 任务队列为空）
             则返回添加失败
             */
            if (rs >= SHUTDOWN &&
                    ! (rs == SHUTDOWN &&
                            firstTask == null &&
                            ! workQueue.isEmpty()))
                return false;
t'r'
            // 自旋操作增加 state 中线程数量
            for (;;) {
                int wc = workerCountOf(c);
                // 线程数量已经不小于 CAPACITY 或者根据 core 参数判断是否
                // 满足数量限制的要求
                // （core 为 true 时必须小于 corePoolSize；为 false 必须
                // 小于 maximumPoolSize）
                if (wc >= CAPACITY ||
                        wc >= (core ? corePoolSize : maximumPoolSize))
                    return false;
                // 使用 CAS 线程数自增，然后退出自旋操作（break 打破外部循环）
                if (compareAndIncrementWorkerCount(c))
                    break retry;
                c = ctl.get();  // Re-read ctl
                // 如果 runState 改变了，从外层循环重新开始（continue 继续外层循环）
                if (runStateOf(c) != rs)
                    continue retry;
                // else 继续内层循环
                // else CAS failed due to workerCount change; retry inner loop
            }
        }

        // 状态修改成功，可以开始创建新的 Worker 了
        boolean workerStarted = false;
        boolean workerAdded = false;
        Worker w = null;
        try {
            w = new Worker(firstTask);
            final Thread t = w.thread;
            if (t != null) {
                final ReentrantLock mainLock = this.mainLock;
                mainLock.lock();
                try {
                    // 加锁之后再次检查线程池的状态，防止加锁过程中状态被修改
                    int rs = runStateOf(ctl.get());

                    // 如果还没有 SHUTDOWN （即 RUNNING）或者正在
                    // SHUTDOWN 且 firstTask 为空，才可以添加 Worker
                    // 第二种情况没有运行任务，只是添加了线程而已
                    if (rs < SHUTDOWN ||
                            (rs == SHUTDOWN && firstTask == null)) {
                        // 如果线程已经开启了，抛出 IllegalThreadStateException 异常
                        if (t.isAlive()) // precheck that t is startable
                            throw new IllegalThreadStateException();
                        // workers 类型为 HashSet，由于 HashSet 线程不安全，
                        // 所以需要加锁
                        workers.add(w);
                        int s = workers.size();
                        // 更新最大线程池大小 largestPoolSize
                        if (s > largestPoolSize)
                            largestPoolSize = s;
                        // 添加成功
                        workerAdded = true;
                    }
                } finally {
                    mainLock.unlock();
                }
                if (workerAdded) {
                    // 添加成功，开启线程
                    t.start();
                    workerStarted = true;
                }
            }
        } finally {
            if (! workerStarted)
                // 开启失败，调用 addWorkerFailed 方法移除失败的 worker
                addWorkerFailed(w);
        }
        return workerStarted;
    }
```

**addWorkerFailed**

addWorkerFailed 方法较简单，只需要从集合中删除创建失败的线程，然后将线程计数减 1 即可。

回滚操作完成后，调用了 tryTerminate 方法。此方法必须在任何可能导致终止的行为之后被调用，例如减少工作线程数，移除队列中的任务，在工作线程运行完毕后处理工作线程退出逻辑的方法 processWorkerExit 之后也应该调用。

```java
    /**
     * 回滚创建 Worker 的操作。
     * - 从 workers 中删除该 worker
     * - 减小 worker 计数
     * - 再次检查是否终止，防止它的存在阻止了 termination
     */
    private void addWorkerFailed(Worker w) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 删除
            if (w != null)
                workers.remove(w);
            // 计数减一
            decrementWorkerCount();
            // 尝试终止
            tryTerminate();
        } finally {
            mainLock.unlock();
        }
    }
```

#### run

**runWorker**

Worker 作为一个工作线程的主要操作（Worker 继承自 Runnable，实现 run 方法）在 runWorker 函数中实现。首先获取 firstTask，并将 firstTask 置为 null，然后进入线程漫长的不断执行任务的循环中。如果 firstTask 不为 null，直接执行 firstTask 即可，否则不断从任务队列获取任务（getTask）。循环的终止条件为 firstTask 为 null，且无法从任务队列中获取到任务（没有待执行的任务了）。每一次循环都需要加锁（加自身的锁，每一个 Worker 都是一个锁），防止 shutdown 时停止了此线程。

```java
    /**
     * 主 worker 运行循环。重复从任务队列获取任务并执行，同时处理一些问题：
     *
     * 1. 我们可能是从第一个初始的任务开始的，在这种情况下，不需要获取
     * 第一个。否则，只要线程池状态是 RUNNING，则需要通过 getTask 获取
     * 任务。如果 getTask 返回 null，则工作线程将有线程池状态更改或配置参数
     * 而退出。
     *
     * 2. 在运行任何任务之前，锁被获取以防止任务执行过程中其他的线程池中断
     * 发生，然后确保除非线程池停止，否则线程不会有中断集。
     *
     * 3. 每一个任务运行之前都调用 beforeExecute，这可能会抛出异常，在
     * 这种情况下，不处理任何任务，让线程死亡（用 completedAbruptly 中止
     * 循环）
     *
     * 4. 假设 beforeExecute 正常完成，运行这个任务，收集它抛出的任何异常
     * 并发送给 afterExecute。我们分别处理 RuntimeException, Error 和任意
     * 可抛出的对象。因为我们不能在 Runnable.run 中重新跑出 Throwables，
     * 所以在抛出时将它们封装在 Error 中（到线程的 UncaughtExceptionHandler）
     * 任何抛出的异常也会导致线程死亡。
     *
     * 5. 在 task.run 完成后，调用 afterExecute，它也可能抛出一个异常，这会
     * 导致线程死亡。
     *
     * @param w the worker
     */
    final void runWorker(Worker w) {
        // 获取当前线程
        Thread wt = Thread.currentThread();
        // 获取当前线程的 task（firstTask）
        Runnable task = w.firstTask;
        w.firstTask = null;
        // 初始化时状态为 -1，此刻设置为 0，表示可以获取锁，并执行任务
        w.unlock(); // allow interrupts
        boolean completedAbruptly = true;
        try {
            // 当 task 不为 null 或者从 getTask 取出的任务不为 null 时
            // 不断从任务队列中获取任务来执行
            while (task != null || (task = getTask()) != null) {
                // 加锁，不是为了防止并发执行任务，为了在 shutdown 时不终止
                // 正在运行的 worker
                // worker 本身就是一个锁，那么每个 worker 就是不同的锁
                w.lock();
                // 如果线程被停止，确保需要设置中断位的线程设置了中断位
                // 如果没有，确保线程没有被中断。清除中断位时需要再次检查以
                // 以应对 shutdownNow。
                // 如果线程池状态已经至少是 STOP，则中断
                // Thread.interrupted 判断是否中断，并且将中断状态重置为未中断，
                // 所以 Thread.interrupted() && runStateAtLeast(ctl.get(), STOP)
                // 的作用是当状态低于 STOP 时，确保不设置中断位。
                // 最后再次检查 !wt.isInterrupted() 判断是否应该中断
                if ((runStateAtLeast(ctl.get(), STOP) ||
                        (Thread.interrupted() &&
                                runStateAtLeast(ctl.get(), STOP))) &&
                        !wt.isInterrupted())
                    wt.interrupt();
                try {
                    beforeExecute(wt, task);
                    Throwable thrown = null;
                    try {
                        // 执行 Runnable
                        task.run();
                    } catch (RuntimeException x) {
                        thrown = x; throw x;
                    } catch (Error x) {
                        thrown = x; throw x;
                    } catch (Throwable x) {
                        thrown = x; throw new Error(x);
                    } finally {
                        afterExecute(task, thrown);
                    }
                } finally {
                    // task 置为 null
                    // 记录完成任务数加一
                    // 解锁
                    task = null;
                    w.completedTasks++;
                    w.unlock();
                }
            }
            // while 执行完毕后设置 completedAbruptly 标志位为 false，表示正常退出
            completedAbruptly = false;
        } finally {
            // 1. 将 worker 从数组 workers 里删除掉；
            // 2. 根据布尔值 allowCoreThreadTimeOut 来决定是否补充新的 Worker 进数组workers
            processWorkerExit(w, completedAbruptly);
        }
    }
```

**getTask**

getTask 函数用于从任务队列中获取任务。它的作用并不仅仅是获取任务，还包括对线程是否应该存活下去的判断。如果 getTask 返回 null，说明线程池已停止，或者没有任务了，或者等待超时了，回到 runWorker 之后下一步要做的就是删除该 Worker。

首先获取线程池状态，根据状态判断能否允许获取任务。如果出现线程池已经 STOP、线程池被 SHUTDOWN 且任务队列为空的情况，应该返回 null。

当状态符合要求时，继续判断是否符合核心线程数/最大线程数的要求：如果工作线程数大于能容纳的最大线程数或者线程等待超时等情况时，此方法返回 null。

尝试从阻塞队列中获取任务，如果超时了还没获取到也会返回 null，如果获取到了则返回获取到的任务。

如果是空闲的核心线程，不会返回 null，而是在 workQueue.take 里面等待新的任务。

```java
    /**
     * 获取等待队列中的任务。基于当前线程池的配置来决定执行任务阻塞或等待
     * 或返回 null。在以下四种情况下会引起 worker 退出，并返回 null：
     * 1. 工作线程数超过 maximumPoolSize。
     * 2. 线程池已停止。
     * 3. 线程池已经 shutdown，且等待队列为空。
     * 4. 工作线程等待任务超时。
     *
     * 并不仅仅是简单地从队列中拿到任务就结束了。
     *
     * @return task, or null if the worker must exit, in which case
     *         workerCount is decremented
     */
    private Runnable getTask() {
        boolean timedOut = false; // Did the last poll() time out?

        for (;;) {
            // 还是首先获取线程池状态
            int c = ctl.get();
            int rs = runStateOf(c);

            // 首先根据状态判断当前线程该不该存活
            // 状态为以下两种情况时会 workerCount 减 1，并返回 null
            // 1. 状态为 SHUTDOWN，且 workQueue 为空
            // （说明在 SHUTDOWN 状态线程池中的线程还是会继续取任务执行）
            // 2. 线程池状态为 STOP
            if (rs >= SHUTDOWN && (rs >= STOP || workQueue.isEmpty())) {
                decrementWorkerCount();
                return null;
                // 返回 null，runWorker 中当前线程会退出 while 循环，然后执行
                // processWorkerExit
            }

            int wc = workerCountOf(c);

            // 然后根据超时限制和核心线程数判断当前线程该不该存活
            // timed 用于判断是否需要超时控制。
            // 在 allowCoreThreadTimeOut 中设置过或者线程数超过核心线程数了
            // 就需要超时控制。
            // allowCoreThreadTimeOut 表示就算是核心线程也会超时
            boolean timed = allowCoreThreadTimeOut || wc > corePoolSize;

            // 1. 线程数超过最大线程数的限制了（运行过程中修改过 maximumPoolSize）
            // 或者已经超时（timed && timedOut 为 true 表示需要进行超时控制
            // 且已经超时）
            // 2. 线程数 workerCount 大于 1 或者任务队列为空（保证 wc 可以减 1）
            if ((wc > maximumPoolSize || (timed && timedOut))
                    && (wc > 1 || workQueue.isEmpty())) {
                if (compareAndDecrementWorkerCount(c))
                    return null;
                continue;
            }

            try {
                // workQueue.poll 表示如果在 keepAliveTime 时间内阻塞队列还是没有任务，则返回 null
                // timed 为 true 则调用有时间控制的 poll 方法进行超时控制，否则通过
                // take 方法获取
                Runnable r = timed ?
                        workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS) :
                        workQueue.take();
                // 获取到任务，立即返回
                if (r != null)
                    return r;
                // 如果 r 等于 null，说明已经超时，设置 timedOut 为 true，在下次
                // 自旋时回收
                timedOut = true;
            } catch (InterruptedException retry) {
                // 发生中断，设置成没有超时，并继续执行
                timedOut = false;
            }
        }
    }
```

**processWorkerExit**

从 runWorker 执行任务的循环跳出后，表明此线程的任务已经执行完毕了，尝试通过 processWorkerExit 从线程集合中删除该线程，并判断是否需要补充新的线程。删除过后同样要调用 tryTerminate。

```java
    /**
     * 为正在死亡的 worker 清理和登记。仅限工作线程调用。除非设置了 completedAbruptly，
     * 否则假定 workCount 已经被更改了。此方法从 worker 集合中移除线程，
     * 如果线程因任务异常而退出，或者运行的工作线程数小于 corePoolSize，
     * 或者队列非空但没有工作线程，则可能终止线程池或替换工作线程。
     *
     * @param w the worker
     * @param completedAbruptly if the worker died due to user exception
     */
    private void processWorkerExit(Worker w, boolean completedAbruptly) {
        // 如果此变量为 true，需要将 workerCount 减一
        if (completedAbruptly) // If abrupt, then workerCount wasn't adjusted
            decrementWorkerCount();

        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 移除当前线程
            completedTaskCount += w.completedTasks;
            workers.remove(w);
        } finally {
            mainLock.unlock();
        }

        // 尝试终止线程池
        tryTerminate();

        int c = ctl.get();
        // 线程池状态小于 STOP，没有终止
        if (runStateLessThan(c, STOP)) {
            if (!completedAbruptly) {
                // 正常退出
                // 如果 allowCoreThreadTimeOut 为 true，就算是核心线程，只要空闲，
                // 都要移除
                // min 获取当前核心线程数
                int min = allowCoreThreadTimeOut ? 0 : corePoolSize;
                // 等待队列不为空，且没有工作线程
                if (min == 0 && ! workQueue.isEmpty())
                    min = 1;
                // 工作线程数大于核心线程数，直接返回（删了就删了，没有影响）
                if (workerCountOf(c) >= min)
                    return; // replacement not needed
            }
            // 如果用户任务执行异常导致线程退出（不是正常退出）
            // 原线程不应该被删除，应该添加新的线程替换它
            addWorker(null, false);
        }
    }
```

**tryTerminate**

此方法用于尝试终止线程池，但只有当严格满足线程池终止的条件时，才会完全终止线程池。

首先仍然要判断线程池状态。如果线程池的状态为 RUNNING 或 （TIDYING 或 TERMINATED）或（SHUTDOWN 且任务队列不为空），说明线程池不应该被终止或者已经有其它线程在终止了，立即返回，不执行后面的操作。

tryTerminate 在所有可能导致终止线程池的行为（例如减少线程数、移除队列中的任务等）之后调用。每一个线程在消亡时都会调用 tryTerminate，如果还有空闲线程，tryTerminate 只会终止一个空闲线程，然后直接返回。线程池中只有最后一个线程能执行 tryTerminate 的后半部分，也就是把状态改成 TIDYING，最后改成 TERMINATED，表示线程池被停止。

```java
    /**
     * 如果当前状态为（SHUTDOWN 且 线程池和队列为空）或者（STOP
     * 且线程池为空）,转换到 TERMINATED 状态，。如果有资格终止，但 workerCount
     * 不是零，中断空闲的线程，以确保 shutdown 的信号传播。此方法必须在
     * 任何可能导致终止的动作之后调用——以减少工作线程数量或在 shutdown
     * 期间从队列中删除任务。此方法是非私有的，ScheduledThreadPoolExecutor
     * 也可以访问。
     *
     * 如果线程池状态为 RUNNING 或 （TIDYING 或 TERMINATED）或
     * （SHUTDOWN 且任务队列不为空），不终止或执行任何操作，直接返回。
     *
     * tryTerminate 用于尝试终止线程池，在 shutdow、shutdownNow、remove
     * 中均是通过此方法来终止线程池。此方法必须在任何可能导致终止的行为
     * 之后被调用，例如减少工作线程数，移除队列中的任务，或者是在工作线程
     * 运行完毕后处理工作线程退出逻辑的方法 processWorkerExit。
     * 如果线程池可被终止（状态为 SHUTDOWN 并且等待队列和池任务都为空，
     * 或池状态为 STOP 且池任务为空），调用此方法转换线程池状态为 TERMINATED。
     * 如果线程池可以被终止，但是当前工作线程数大于 0，则调用
     * interruptIdleWorkers方法先中断一个空闲的工作线程，用来保证池
     * 关闭操作继续向下传递。
     */
    final void tryTerminate() {
        for (;;) {
            int c = ctl.get();
            // 线程池状态为 RUNNING 或 （TIDYING 或 TERMINATED）或
            // （SHUTDOWN 且任务队列不为空），直接返回
            if (isRunning(c) ||
                    runStateAtLeast(c, TIDYING) ||
                    (runStateOf(c) == SHUTDOWN && ! workQueue.isEmpty()))
                return;
            // 如果工作线程数 workCount 不为 0，调用函数关闭一个空闲线程，然后返回
            // (只关闭一个的原因可能是遍历所有的 worker 消耗太大。)
            if (workerCountOf(c) != 0) { // Eligible to terminate
                interruptIdleWorkers(ONLY_ONE);
                return;
            }

            // 线程数为 0 且（状态为 STOP 或者（状态为 SHUTDOWN 且
            // 任务队列为空）），此处为线程池状态转化图中满足 SHUTDOWN
            // 或 STOP 转化到 TIDYING 的情况。
            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                // 如果成功将状态转化成了 TIDYING，在调用 terminated 方法完成
                // 将 TIDYING 转化到 TERMINATED 的后续操作
                if (ctl.compareAndSet(c, ctlOf(TIDYING, 0))) {
                    try {
                        terminated();
                    } finally {
                        // 最后将状态设为 TERMINATED 即可
                        ctl.set(ctlOf(TERMINATED, 0));
                        termination.signalAll();
                    }
                    return;
                }
            } finally {
                mainLock.unlock();
            }
            // else retry on failed CAS
        }
    }
```

**interruptIdleWorkers**

中断空闲的线程。在 tryTerminate 中调用时 onlyOne 参数为 true，其他地方调用时 onlyOne 为 false。

空闲指的是没有上锁的线程，正在执行 getTask 的工作线程也可能被中断。

```java
    /**
     * 中断可能正在等待任务的线程（空闲线程），以便他们可以检查终止或
     * 配置更改。忽略 SecurityExceptions （防止一些线程没有被中断）
     *
     * @param onlyOne If true, interrupt at most one worker. This is
     * called only from tryTerminate when termination is otherwise
     * enabled but there are still other workers.  In this case, at
     * most one waiting worker is interrupted to propagate shutdown
     * signals in case all threads are currently waiting.
     * Interrupting any arbitrary thread ensures that newly arriving
     * workers since shutdown began will also eventually exit.
     * To guarantee eventual termination, it suffices to always
     * interrupt only one idle worker, but shutdown() interrupts all
     * idle workers so that redundant workers exit promptly, not
     * waiting for a straggler task to finish.
     */
    private void interruptIdleWorkers(boolean onlyOne) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (Worker w : workers) {
                Thread t = w.thread;
                // 如果线程没有被中断且能获取到锁（能获取到说明它很闲，因为已经在
                // 执行任务的线程都已经获取到锁了，getTask 方法没有加锁），
                // 则尝试中断
                if (!t.isInterrupted() && w.tryLock()) {
                    try {
                        t.interrupt();
                    } catch (SecurityException ignore) {
                    } finally {
                        w.unlock();
                    }
                }
                // 如果 onlyOne 为 true，仅中断一个线程
                if (onlyOne)
                    break;
            }
        } finally {
            mainLock.unlock();
        }
    }
```

#### shutdown

**shutdown**

把状态改成 SHUTDOWN，进行有序的 shutdown，不接受新的任务，继续完成已经提交的任务，清理所有空闲线程。

```java
    /**
     * 启动有序的 shutdown，在此过程中执行以前已经提交的任务，但不接受新
     * 的任务。如果已经 shutdown，调用将没有其它效果。
     *
     * 此方法不等待以前提交的任务完成执行。使用 awaitTermination 来完成。
     *
     * @throws SecurityException {@inheritDoc}
     */
    public void shutdown() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 检查有没有权限
            checkShutdownAccess();
            // 将状态转变为参数中指定的状态，此处为 SHUTDOWN
            advanceRunState(SHUTDOWN);
            // 终止所有空闲线程
            interruptIdleWorkers();
            onShutdown(); // hook for ScheduledThreadPoolExecutor
        } finally {
            mainLock.unlock();
        }
        tryTerminate();
    }
```

**shutdownNow**

把状态改成 STOP，粗暴地中断所有正在运行的线程，还没有执行的任务也不执行了，直接返回。

```java
    /**
     * 尝试停止所有正在执行的任务，停止等待线程，并返回等待执行的任务列表。
     * 从此方法返回时，将从任务队列中删除这些任务。
     *
     * 此方法不会等待正在活跃执行的任务终止。使用 awaitTermination 来完成。
     *
     * 除了尽最大努力停止处理正在执行的任务之外，没有任何其他承诺。此实现
     * 通过 Thread.interrupt 来取消任务，所以没有响应中断的任务可能永远不会
     * 终止。
     *
     * @throws SecurityException {@inheritDoc}
     */
    public List<Runnable> shutdownNow() {
        List<Runnable> tasks;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 检查权限
            checkShutdownAccess();
            // 将状态变成 STOP
            advanceRunState(STOP);
            // 中断 Worker
            interruptWorkers();
            // 调用 drainQueue 将队列中未处理的任务移到 tasks 里
            tasks = drainQueue();
        } finally {
            mainLock.unlock();
        }
        tryTerminate();
        return tasks;
    }
```

#### RejectedExecutionHandler

此类中默认实现了以下四种拒绝策略（继承自 RejectedExecutionHandler 类，实现 rejectedExecution 方法）：

**CallerRunsPolicy**

在调用者线程（而非线程池中的线程）中直接执行任务。

```java
    /**
     * 被拒绝任务的处理程序，直接在 execute 方法的调用线程中运行被拒绝的
     * 任务，除非线程池已经被 shutdown 了，在这种情况下被丢弃。
     */
    public static class CallerRunsPolicy implements RejectedExecutionHandler {
        /**
         * 创建 CallerRunsPolicy
         */
        public CallerRunsPolicy() { }

        /**
         * 在调用者的线程中执行任务 r，除非执行者被 shutdown，这种情况下
         * 任务被忽略。
         *
         * @param r the runnable task requested to be executed
         * @param e the executor attempting to execute this task
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            // 如果线程池没有被 shut down，则直接运行
            if (!e.isShutdown()) {
                r.run();
            }
        }
    }
```

**AbortPolicy（默认）**

直接抛出 RejectedExecutionException 异常。程序中需要处理好此异常，不然会影响后续任务的执行。

```java
    /**
     * 直接抛出 RejectedExecutionException 异常
     */
    public static class AbortPolicy implements RejectedExecutionHandler {
        /**
         * 创建 AbortPolicy。
         */
        public AbortPolicy() { }

        /**
         * 总是抛出 RejectedExecutionException 异常。
         *
         * @param r the runnable task requested to be executed
         * @param e the executor attempting to execute this task
         * @throws RejectedExecutionException always
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            throw new RejectedExecutionException("Task " + r.toString() +
                    " rejected from " +
                    e.toString());
        }
    }
```

**DiscardPolicy**

直接忽略任务，不执行任何操作。

```java
    /**
     * 直接忽略，不会抛出任何异常
     */
    public static class DiscardPolicy implements RejectedExecutionHandler {
        /**
         * 创建 DiscardPolicy。
         */
        public DiscardPolicy() { }

        /**
         * 直接忽略。
         *
         * @param r the runnable task requested to be executed
         * @param e the executor attempting to execute this task
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
        }
    }
```

**DiscardOldestPolicy**

丢弃任务队列中最老的任务，并将对此任务执行 execute。

```java
    /**
     * 将任务队列中最老的未处理请求删除，然后 execute 任务。除非线程池被
     * shut down，这种情况下任务被丢弃。
     */
    public static class DiscardOldestPolicy implements RejectedExecutionHandler {
        /**
         * 创建 DiscardOldestPolicy。
         */
        public DiscardOldestPolicy() { }

        /**
         * 获取并忽略线程池中下一个将执行的任务（任务队列中最老的任务），
         * 然后重新尝试执行任务 r。除非线程池关闭，在这种情况下，任务 r 将被
         * 丢弃。
         *
         * @param r the runnable task requested to be executed
         * @param e the executor attempting to execute this task
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                e.getQueue().poll();
                e.execute(r);
            }
        }
    }
```

### 为什么要使用线程池

1. 由线程池创建、调度、监控和销毁所有线程，控制线程数量，避免出现线程泄露，方便管理。

2. 重复利用已创建的线程执行任务，不需要持续不断地重复创建和销毁线程，降低资源消耗。

3. 直接从线程池空闲的线程中获取工作线程，立即执行任务，不需要每次都创建新的线程，从而提高程序的响应速度。

### Executors 和常用线程池

Executors 类，提供了一系列工厂方法用于创建线程池，返回的线程池都实现了 ExecutorService 接口，常用以下四种线程池（不包括 ForkJoinPool）：

* newCachedThreadPool: 线程数量没有限制（核心线程数设置为 0，其实有，最大限制为 Integer.MAX_VALUE），如果线程等待时间过长（默认为超过 60 秒），该空闲线程会自动终止。

* newFixedThreadPool: 指定线程数量（核心线程数和最大线程数相同），在线程池没有任务可运行时，不会释放工作线程，还将占用一定的系统资源。

* newSingleThreadPool: 只包含一个线程的线程池（核心线程数和最大线程数均为 1），保证任务顺序执行。

* newScheduledThreadPool: 定长线程池，定时及周期性执行任务。

详见 [常用线程池](https://blog.csdn.net/Victorgcx/article/details/103539875)。

### 使用 ThreadPoolExecutor 而不是 Executors 创建线程池

Executors 工厂创造线程池对象弊端如下：

* FixedThreadPool 和 SingleThreadPool 允许的请求队列长度为 Integer.MAX_VALUE，可能会堆积大量请求，从而导致 OOM。

* CacheThreadPool 和 ScheduledThreadPool 允许的创建线程数量为 Integer.MAX_VALUE，可能会创建大量线程，从而导致 OOM。

### 池化

统一管理资源，包括服务器、存储、和网络资源等等。通过共享资源，使用户在低投入中获益。

* 内存池(Memory Pooling)：预先申请内存，提升申请内存速度，减少内存碎片。
* 连接池(Connection Pooling)：预先申请数据库连接，提升申请连接的速度，降低系统的开销。
* 实例池(Object Pooling)：循环使用对象，减少资源在初始化和释放时的昂贵损耗。

### 参考

* [JUC源码分析-线程池篇（一）：ThreadPoolExecutor](https://www.jianshu.com/p/7be43712ef21)

* [ThreadPoolExecutor源码解析](https://www.jianshu.com/p/a977ab6704d7)

* [ThreadPoolExecutor源码剖析](https://blog.csdn.net/qq_30572275/article/details/80543921)

* [java多线程系列：ThreadPoolExecutor源码分析](https://www.cnblogs.com/fixzd/p/9253203.html)