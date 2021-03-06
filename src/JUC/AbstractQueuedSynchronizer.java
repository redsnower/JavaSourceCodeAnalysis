/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package JUC;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.locks.AbstractOwnableSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;

import sun.misc.Unsafe;

/**
 * 提供一个框架来实现基于先进先出（FIFO）等待队列的阻塞锁和相关同步器
 * （信号量，时间等）。此类被用来作为大多数同步器的重要基础，这些同步器
 * 依赖于单个原子 int 值来表示状态。子类必须定义 protected 方法来改变这个
 * 状态，这些方法定义了这个状态对于被获取或释放的对象意味着什么。在此
 * 基础上，这个类中其它的方法执行所有的排队和阻塞机制。子类可以维护其它
 * 的状态字段，但是只有使用方法 getState, setState, compareAndSetState
 * 自动更新的 int 值才会被同步跟踪。
 *
 * 子类应该定义为非 public 的内部辅助类，用于实现其封闭类的同步属性。
 * AbstractQueuedSynchronizer 类不实现任何同步接口，相反，它定义了
 * acquireInterruptibly 等方法，这些方法可以被具体的锁和相关的同步器适当地
 * 调用来实现它们的 public 方法。
 *
 * 此类支持默认独占模式和共享模式中的一种或两种。当以独占模式获取时，
 * 其它线程尝试获取都不会成功。由多个线程获取的共享模式有可能会（但不一定）
 * 会成功。在不同的模式下等待的线程共享相同的 FIFO 队列。通常，实现的子类
 * 只支持其中一种模式，但是两种模式都可以发挥作用，例如在 ReadWriteLock
 * 中。只支持独占或共享模式的子类不需要定义和未使用模式相关的方法。
 *
 * 这个类定义了一个嵌套的 ConditionObject 类，可由支持独占模式的子类作为
 * Condition 的实现，该子类的方法 isHeldExclusively 报告当前线程是否独占。
 * 使用 getState 方法调用 release 完全释放该对象，使用 acquire 获取给定的
 * 状态值。否则，没有 AbstractQueuedSynchronizer 的方法会创建这样的
 * condition，因此如果不能满足这样的限制，不要使用它。ConditionObject
 * 的行为取决于其同步器实现的语义。
 *
 * 此类提供了内部队列的检查，检测和检测方法，以及 condition 对象的类似方法。
 * 这些可以根据需要使用 AbstractQueuedSynchronizer 导出到类中以实现其
 * 同步机制。
 *
 * 此类的序列化仅存储基础原子 integer 的维护状态，因此反序列化的对象具有
 * 空线程队列。需要序列化的典型子类会定义一个 readObject 方法，在反序列
 * 化时，将其恢复到已知的初始状态。
 *
 * 用法：
 *
 * 想要使用这个类作为同步器的基础，需要重新定义以下方法，在调用 getState,
 * setState, compareAndSetState 时检查或修改同步状态：
 * tryAcquire
 * tryRelease
 * tryAcquireShared
 * tryReleaseShared
 * isHeldExclusively
 *
 * 默认情况下这些方法中每一个都会抛出 UnsupportedOperationException
 * 异常。这些方法的实现必须是在内部线程安全的，或者必须简短且不阻塞。
 * 要使用此类必须定义这些方法。所有其他的方法都声明为 final，因为它们
 * 不能独立变化。
 *
 * 你可能还会发现从 AbstractOwnableSynchronizer 继承的方法对跟踪拥有
 * 独占同步器的线程很有用。鼓励用户使用它们，这将启用监视和诊断工具，以
 * 帮助用户确定那些线程持有锁。
 *
 * 即使这些类基于内部 FIFO 队列，它也不会自动执行 FIFO 获取策略。独占
 * 同步操作的核心采取以下形式：
 * Acquire:
 *     while (!tryAcquire(arg)) {
 *         // 如果没有在队列中则进入队列
 *         // 可能会阻塞当前线程
 *     }
 * Release:
 *     if (tryRelease(arg))
 *        // 释放队列的第一个线程
 *
 * （共享锁类似，但可能涉及级联信号。）
 *
 * 在入队列之前进行 acquire 检查，所以新的正在获取的线程可能会插入到被阻塞
 * 和排队的线程之前。但是，如果需要，你可以定义 tryAcquire 或 tryAcquireShared
 * 以通过内部调用一种或多种检查方法来禁用插入，从而提供一个公平的 FIFO
 * 顺序。特别是，如果 hasQueuedPredecessors（一种专门为公平的同步器设计
 * 的方法）返回 true，大多数公平的同步器可以定义 tryAcquire 以返回 true。
 * 其它变化也是有可能的。
 *
 * 对于默认插入（也被称为贪婪，放弃和避免拥护）策略，吞吐量和可伸缩性
 * 通常最高。尽管这不能保证公平，也不会出现饥饿现象，但是可以让较早入队
 * 的线程在较晚入队的线程之间重新竞争。同样，尽管 acquire 不是通常意义上
 * 的自旋，但它可能会在阻塞之前执行 tryAcquire 多次调用，并插入其它计算。
 * 仅仅短暂地保持排他同步的时候，这将给自旋提供大量好处，而不会带来过多
 * 负担。如果需要的话，你可以通过在调用之前对 acquire 进行 “fast-path” 检查
 * 来增强此功能，如果同步器不被竞争的话，可能会预先检查 hashContended
 * 或 hashQueuedThread。
 *
 * 此类为同步提供了有效和可扩展的基础，部分是通过将其使用范围限定于依赖
 * int 状态，acquire 和 release 参数，内部 FIFO 等待队列的同步器。如果这
 * 还不够，你可以使用原子类，你自定义的 Queue 类，和
 * LockSupport 阻塞支持来从底层创建自己的同步器。
 *
 * 使用案例：
 *
 * 这是一个不可重入的互斥锁类，使用值 0 表示非锁定状态，1 表示锁定状态。
 * 尽管非重入锁并不严格记录当前的所有者线程，此类还是选择这样做来让监视
 * 更方便。她还支持 condition，和一种检测方法：
 *
 * class Mutex implements Lock, java.io.Serializable {
 *
 *   // 我们的内部辅助类
 *   private static class Sync extends AbstractQueuedSynchronizer {
 *     // 描述是否是锁定状态
 *     protected boolean isHeldExclusively() {
 *       return getState() == 1;
 *     }
 *
 *     // 如果状态值是 0 就获取锁
 *     public boolean tryAcquire(int acquires) {
 *       assert acquires == 1; // Otherwise unused
 *       if (compareAndSetState(0, 1)) {
 *         setExclusiveOwnerThread(Thread.currentThread());
 *         return true;
 *       }
 *       return false;
 *     }
 *
 *     // 设置状态值为 0，释放锁
 *     protected boolean tryRelease(int releases) {
 *       assert releases == 1; // Otherwise unused
 *       if (getState() == 0) throw new IllegalMonitorStateException();
 *       setExclusiveOwnerThread(null);
 *       setState(0);
 *       return true;
 *     }
 *
 *     // 提供一个 Condition
 *     Condition newCondition() { return new ConditionObject(); }
 *
 *     // 正确地反序列化
 *     private void readObject(ObjectInputStream s)
 *         throws IOException, ClassNotFoundException {
 *       s.defaultReadObject();
 *       setState(0); // reset to unlocked state
 *     }
 *   }
 *
 *   private final Sync sync = new Sync();
 *
 *   public void lock()                { sync.acquire(1); }
 *   public boolean tryLock()          { return sync.tryAcquire(1); }
 *   public void unlock()              { sync.release(1); }
 *   public Condition newCondition()   { return sync.newCondition(); }
 *   public boolean isLocked()         { return sync.isHeldExclusively(); }
 *   public boolean hasQueuedThreads() { return sync.hasQueuedThreads(); }
 *   public void lockInterruptibly() throws InterruptedException {
 *     sync.acquireInterruptibly(1);
 *   }
 *   public boolean tryLock(long timeout, TimeUnit unit)
 *       throws InterruptedException {
 *     return sync.tryAcquireNanos(1, unit.toNanos(timeout));
 *   }
 * }}
 *
 * 这是一个类似 CountDownLatch 的类，但它只需要一个单独的 signal 来触发。
 * 如果一个 latch 是非独占的，它使用可共享的 acquire 和 release 方法。
 *
 * class BooleanLatch {
 *
 *   private static class Sync extends AbstractQueuedSynchronizer {
 *     boolean isSignalled() { return getState() != 0; }
 *
 *     protected int tryAcquireShared(int ignore) {
 *       return isSignalled() ? 1 : -1;
 *     }
 *
 *     protected boolean tryReleaseShared(int ignore) {
 *       setState(1);
 *       return true;
 *     }
 *   }
 *
 *   private final Sync sync = new Sync();
 *   public boolean isSignalled() { return sync.isSignalled(); }
 *   public void signal()         { sync.releaseShared(1); }
 *   public void await() throws InterruptedException {
 *     sync.acquireSharedInterruptibly(1);
 *   }
 * }}
 *
 * @since 1.5
 * @author Doug Lea
 */
public abstract class AbstractQueuedSynchronizer
        extends AbstractOwnableSynchronizer
        implements java.io.Serializable {

    private static final long serialVersionUID = 7373984972572414691L;

    /**
     * 创建初始同步状态为 0 的 AbstractQueuedSynchronizer 实例。
     */
    protected AbstractQueuedSynchronizer() { }

    /**
     * 等待队列的节点类。
     */

    static final class Node {
        /** 节点在共享模式下等待的标记 */
        static final Node SHARED = new Node();
        /** 节点在独占模式下等待的标记 */
        static final Node EXCLUSIVE = null;

        // 等待状态的值为 0 表示当前节点在 sync 队列中，等待着获取锁
        /** 表示等待状态的值，为 1 表示当前节点已被取消调度，进入这个状态
         * 的节点不会再变化 */
        static final int CANCELLED =  1;
        /** 表示等待状态的值，为 -1 表示当前节点的后继节点线程被阻塞，正在
         * 等待当前节点唤醒。后继节点入队列的时候，会将前一个节点的状态
         * 更新为 SIGNAL */
        static final int SIGNAL    = -1;
        /**  表示等待状态的值，为 -2 表示当前节点等待在 condition 上，当其他
         * 线程调用了 Condition 的 signal 方法后，condition 状态的节点将从
         * 等待队列转移到同步队列中，等到获取同步锁。
         * CONDITION 在同步队列里不会用到*/
        static final int CONDITION = -2;
        /**
         * 表示等待状态的值，为 -3 ，在共享锁里使用，表示下一个 acquireShared
         * 操作应该被无条件传播。保证后续节点可以获取共享资源。
         * 共享模式下，前一个节点不仅会唤醒其后继节点，同时也可能会唤醒
         * 后继的后继节点。
         */
        static final int PROPAGATE = -3;

        /**
         * waitStatus 表示节点状态，有如下几个值（就是上面的那几个）：
         * SIGNAL: 当前节点的后继节点被（或者即将被）阻塞（通过 park），
         * 因此当前节点在释放或者取消时必须接触对后继节点的阻塞。为了避免
         * 竞争，acquire 方法必须首先表明它们需要一个信号，然后然后尝试原子
         * 获取，如果失败则阻塞。
         * CANCELLED：此节点由于超时或中断而取消。节点永远不会离开此状态。
         * 特别是，具有已取消节点的线程不会再阻塞。
         * CONDITION：此节点此时位于条件队列上。在转移之前它不会被用作
         * 同步队列节点，此时状态被设为 0。（这里此值的使用与此字段的其他
         * 用法无关，但是简化了机制）。
         * PROPAGATE：releaseShared 应该被传播到其它节点。这是在 doReleaseShared
         * 中设置的（仅针对头结点），以确保传播能够继续，即使其它操作已经介入了。
         * 0：以上情况都不是
         *
         * 此值以数字形式组织以简化使用。非负值意味着节点不需要发出信号。
         * 因此，大多数代码不需要检查特定的值，只需要检查符号。
         *
         * 对于正常的同步节点此字段初始化为 0，对于田间节点初始化为
         * CONDITION。可以使用 CAS （或者可能的话，使用无条件的 volatile 写）
         * 修改它。
         */
        volatile int waitStatus;

        /**
         * 与当前节点锁依赖的用于检查等待状态的前辈节点建立的连接。在进入
         * 队列时分配，在退出队列时设置为 null（便于垃圾回收）。此外，在查找
         * 一个未取消的前驱节点时短路，这个前驱节点总是存在，因为头结点
         * 绝不会被取消：一个节点只有在成功 acquire 之后才成为头结点。被取消
         * 的线程 acquire 绝不会成功，而且线程只取消自己，不会取消其他节点。
         */
        volatile Node prev;

        /**
         * 与当前节点 unpark 之后的后续节点建立的连接。在入队时分配，在绕过
         * 已取消的前一个节点时调整，退出队列时设置为 null（方便 GC）。入队
         * 操作直到 attachment 之后才会分配其后继节点，所以看到此字段为 null
         * 并不一定意味着节点在队列尾部。但是，如果 next 字段看起来为 null，
         * 我们可以从 tail 往前以进行双重检查。被取消节点的 next 字段设置成指向
         * 其自身而不是 null，以使 isOnSyncQueue 的工作更简单。
         */
        volatile Node next;

        /**
         * 此节点代表的线程。
         */
        volatile Thread thread;

        /**
         * 连接到在 condition 等待的下一个节点。由于条件队列只在独占模式下被
         * 访问，我们只需要一个简单的链式队列在保存在 condition 中等待的节点。
         * 然后他们被转移到队列中重新执行 acquire。由于 condition 只能是排它的，
         * 我们可以通过使用一个字段，保存特殊值来表示共享模式。
         */
        Node nextWaiter;

        /**
         * 如果节点在共享模式中处于等待状态，返回 true。
         */
        final boolean isShared() {
            return nextWaiter == SHARED;
        }

        /**
         * 返回前一个节点，如果为 null 抛出 NullPointerException 异常。
         * 当前一个节点不为 null 时才能使用。非空检查可以省略，此处是为了辅助
         * 虚拟机。
         *
         * @return the predecessor of this node
         */
        final Node predecessor() throws NullPointerException {
            Node p = prev;
            if (p == null)
                throw new NullPointerException();
            else
                return p;
        }

        // 用来创建初始节点或者共享标记
        Node() {    // Used to establish initial head or SHARED marker
        }

        // addWaiter 使用
        Node(Thread thread, Node mode) {     // Used by addWaiter
            this.nextWaiter = mode;
            this.thread = thread;
        }

        //Condition 使用
        Node(Thread thread, int waitStatus) { // Used by Condition
            this.waitStatus = waitStatus;
            this.thread = thread;
        }
    }

    /**
     * 同步队列的头结点，延迟初始化。除了初始化之外，只能通过 setHead
     * 修改。注意：如果 head 存在的话，其状态必须保证不是 CANCELLED。
     */
    private transient volatile Node head;

    /**
     * 同步队列的尾节点，延迟初始化。只能通过入队方法添加新的节点。
     */
    private transient volatile Node tail;

    /**
     * 同步状态。
     */
    private volatile int state;

    /**
     * 返回同步状态的当前值。
     * 此操作具有 volatile read 的内存语义。
     * @return current state value
     */
    protected final int getState() {
        return state;
    }

    /**
     * 设置同步状态的值。
     * 返回同步状态的当前值。此操作具有 volatile write 的内存语义。
     * @param newState the new state value
     */
    protected final void setState(int newState) {
        state = newState;
    }

    /**
     * 通过 CAS 的方式设置状态值。
     *
     * @param expect the expected value
     * @param update the new value
     * @return {@code true} if successful. False return indicates that the actual
     *         value was not equal to the expected value.
     */
    protected final boolean compareAndSetState(int expect, int update) {
        // See below for intrinsics setup to support this
        return unsafe.compareAndSwapInt(this, stateOffset, expect, update);
    }

    // Queuing utilities
    // 队列工具

    /**
     * The number of nanoseconds for which it is faster to spin
     * rather than to use timed park. A rough estimate suffices
     * to improve responsiveness with very short timeouts.
     */
    static final long spinForTimeoutThreshold = 1000L;

    /**
     * 把节点添加到队列中，必要时初始化。
     * @param node the node to insert
     * @return node's predecessor
     */
    private Node enq(final Node node) {
        for (;;) {
            Node t = tail;
            // 如果尾节点为 null，需要初始化并设置新的节点为头结点和尾节点。
            if (t == null) { // Must initialize
                // 以 CAS 方式添加，防止多线程添加产生节点覆盖
                if (compareAndSetHead(new Node()))
                    tail = head;
            } else {
                node.prev = t;
                // 以 CAS 方式添加，防止多线程添加产生节点覆盖
                if (compareAndSetTail(t, node)) {
                    t.next = node;
                    return t;
                }
            }
        }
    }

    /**
     * 为当前线程和给定模式创建节点并添加到等待队列队列尾部，并返回当前线程
     * 所在节点。
     *
     * 如果 tail 不为 null，即等待队列已经存在，则以 CAS 的方式将当前线程节点
     * 加入到等待队列的末尾。否则，通过 enq 方法初始化一个等待队列，并返回当前节点。
     *
     * @param mode Node.EXCLUSIVE for exclusive, Node.SHARED for shared
     * @return the new node
     */
    private Node addWaiter(Node mode) {
        Node node = new Node(Thread.currentThread(), mode);
        // 尝试快速入队，失败时载调用 enq 函数的方式入队
        Node pred = tail;
        if (pred != null) {
            node.prev = pred;
            if (compareAndSetTail(pred, node)) {
                pred.next = node;
                return node;
            }
        }
        enq(node);
        return node;
    }

    /**
     * 将队列的头节点设置为指定节点，从而退出队列。仅仅在 acquire 方法中
     * 调用。为了进行 GC 和防止不必要的信号和遍历，将不使用的字段设置为 null。
     *
     * @param node the node
     */
    private void setHead(Node node) {
        head = node;
        node.thread = null;
        node.prev = null;
    }

    /**
     * 唤醒指定节点的后继节点，如果其存在的话。
     * unpark - 唤醒
     * 成功获取到资源之后，调用这个方法唤醒 head 的下一个节点。由于当前
     * 节点已经释放掉资源，下一个等待的线程可以被唤醒继续获取资源。
     *
     * @param node the node
     */
    private void unparkSuccessor(Node node) {

        int ws = node.waitStatus;
        // 如果当前节点没有被取消，更新 waitStatus 为 0。
        if (ws < 0)
            compareAndSetWaitStatus(node, ws, 0);

        /**
         * 待唤醒的线程保存在后继节点中，通常是下一个节点。但是如果已经被
         * 取消或者显然为 null，则从 tail 向前遍历，以找到实际的未取消后继节点。
         */
        Node s = node.next;
        if (s == null || s.waitStatus > 0) {
            s = null;
            for (Node t = tail; t != null && t != node; t = t.prev)
                if (t.waitStatus <= 0)
                    s = t;
        }
        if (s != null)
            LockSupport.unpark(s.thread);
    }

    /**
     * 共享模式下的释放（资源）操作 -- 信号发送给后继者并确保资源传播。
     * （注意：对于独占模式，如果释放之前需要信号，直接调用 head 的
     * unparkSuccessor。）
     *
     * 在 tryReleaseShared 成功释放资源后，调用此方法唤醒后继线程并保证
     * 后继节点的 release 传播（通过设置 head 的 waitStatus 为 PROPAGATE。
     */
    private void doReleaseShared() {
        /**
         * 确保 release 传播，即使有其它的正在 acquire 或者 release。这是试图
         * 调用 head 唤醒后继者的正常方式，如果需要唤醒的话。但如果没有，
         * 则将状态设置为 PROPAGATE，以确保 release 之后传播继续进行。
         * 此外，我们必须在无限循环下进行，防止新节点插入到里面。另外，与
         * unparkSuccessor 的其他用法不同，我们需要知道是否 CAS 的重置操作
         * 失败，并重新检查。
         */
        // 自旋（无限循环）确保释放后唤醒后继节点
        for (;;) {
            Node h = head;
            if (h != null && h != tail) {
                int ws = h.waitStatus;
                if (ws == Node.SIGNAL) {
                    if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0))
                        continue;            // loop to recheck cases
                    // 唤醒后继节点
                    unparkSuccessor(h);
                }
                else if (ws == 0 &&
                        !compareAndSetWaitStatus(h, 0, Node.PROPAGATE))
                    continue;                // loop on failed CAS
            }
            if (h == head)                   // loop if head changed
                break;
        }
    }

    /**
     * 指定队列的 head，并检查后继节点是否在共享模式下等待，如果是且
     * （propagate > 0 或等待状态为 PROPAGATE），则传播。
     *
     * @param node the node
     * @param propagate the return value from a tryAcquireShared
     */
    private void setHeadAndPropagate(Node node, int propagate) {
        // 记录原先的 head，并将 node 节点设置为新的 head。
        Node h = head;
        setHead(node);
        if (propagate > 0 || h == null || h.waitStatus < 0 ||
                (h = head) == null || h.waitStatus < 0) {
            Node s = node.next;
            if (s == null || s.isShared())
                doReleaseShared();
        }
    }

    // Utilities for various versions of acquire
    // 不同版本的 acquire 实现的辅助工具

    /**
     * 取消正在进行的 acquire 尝试。
     * 使 node 不再关联任何线程，并将 node 的状态设置为 CANCELLED。
     *
     * @param node the node
     */
    private void cancelAcquire(Node node) {
        // 如果节点不存在直接忽略
        if (node == null)
            return;

        // node 不再关联任何线程
        node.thread = null;

        // 跳过已经 cancel 的前驱节点，找到一个有效的前驱节点 pred
        // Skip cancelled predecessors
        Node pred = node.prev;
        while (pred.waitStatus > 0)
            node.prev = pred = pred.prev;

        // predNext is the apparent node to unsplice. CASes below will
        // fail if not, in which case, we lost race vs another cancel
        // or signal, so no further action is necessary.
        Node predNext = pred.next;

        // 这里可以使用无条件写代替 CAS。在这个原子步骤之后，其他节点可以
        // 跳过。在此之前，我们不受其它线程的干扰。
        node.waitStatus = Node.CANCELLED;

        // 如果当前节点是 tail，删除自身（更新 tail 为 pred，并使 predNext
        // 指向 null）。
        if (node == tail && compareAndSetTail(node, pred)) {
            compareAndSetNext(pred, predNext, null);
        } else {
            // If successor needs signal, try to set pred's next-link
            // so it will get one. Otherwise wake it up to propagate.
            int ws;
            // 如果 node 不是 tail 也不是 head 的后继节点，将 node 的前驱节点
            // 设置为 SIGNAL，然后将 node 前驱节点的 next 设置为 node 的
            // 后继节点。
            if (pred != head &&
                    ((ws = pred.waitStatus) == Node.SIGNAL ||
                            (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))) &&
                    pred.thread != null) {
                Node next = node.next;
                if (next != null && next.waitStatus <= 0)
                    compareAndSetNext(pred, predNext, next);
            } else {
                // 如果 node 是 head 的后继节点，直接唤醒 node 的后继节点
                unparkSuccessor(node);
            }
            // 辅助垃圾回收
            node.next = node; // help GC
        }
    }

    /**
     * 检查和更新未能成功 acquire 的节点状态。如果线程应该阻塞，返回 true。
     * 这是所有 acquire 循环的主要信号控制。需要 pred == node.prev。
     *
     * @param pred node's predecessor holding status
     * @param node the node
     * @return {@code true} if thread should block
     */
    private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
        int ws = pred.waitStatus;
        // ws 存储前驱节点的状态
        if (ws == Node.SIGNAL)
            /**
             * pred 节点已经将状态设置为 SIGNAL，即 node 已告诉前驱节点自己正在
             * 等到唤醒。此时可以安心进入等待状态。
             */
            return true;
        if (ws > 0) {
            /**
             * 前驱节点已经被取消。跳过前驱节点一直往前找，直到找到一个非
             * CANCEL 的节点，将前驱节点设置为此节点。（中途经过的 CANCEL
             * 节点会被垃圾回收。）
             */
            do {
                node.prev = pred = pred.prev;
            } while (pred.waitStatus > 0);
            pred.next = node;
        } else {
            /**
             * 如果进行到这里 waitStatus 应该是 0 或者 PROPAGATE。说明我们
             * 需要一个信号，但是不要立即 park。在 park 前调用者需要重试。
             * 使用 CAS 的方式将 pred 的状态设置成 SIGNAL。（例如如果 pred
             * 刚刚 CANCEL 就不能设置成 SIGNAL。）
             */
            compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
        }
        // 只有当前驱节点是 SIGNAL 才直接返回 true，否则只能返回 false，
        // 并重新尝试。
        return false;
    }

    /**
     * 中断当前线程
     */
    static void selfInterrupt() {
        Thread.currentThread().interrupt();
    }

    /**
     * 让线程进入等待状态。park 会让线程进入 waiting 状态。在此状态下有
     * 两种途径可以唤醒该线程：被 unpark 或者被 interrupt。Thread 会清除
     * 当前线程的中断标记位。
     * Convenience method to park and then check if interrupted
     *
     * @return {@code true} if interrupted
     */
    private final boolean parkAndCheckInterrupt() {
        LockSupport.park(this);
        return Thread.interrupted();
    }

    /**
     * 以下是各种各样执行 acquire 操作的方式，在独占/共享和控制模式下各不相同。
     */

    /**
     * 等待队列中的线程自旋时，以独占且不可中断的方式 acquire。
     * 用于 condition 等待方式中的 acquire。
     *
     * @param node the node
     * @param arg the acquire argument
     * @return {@code true} if interrupted while waiting
     */
    final boolean acquireQueued(final Node node, int arg) {
        // 标记是否成功拿到资源
        boolean failed = true;
        try {
            // 标记等待过程中是否被中断过
            boolean interrupted = false;
            // 自旋
            for (;;) {
                // 获取 node 的前一个节点
                final Node p = node.predecessor();
                // 如果前一个节点为 head，尝试获取资源
                if (p == head && tryAcquire(arg)) {
                    // 获取成功，将 node 设置为 head，并将 p 的 next 设置为 null，
                    // 以便于回收 p 节点。
                    setHead(node);
                    p.next = null; // help GC
                    // 成功获取资源
                    failed = false;
                    return interrupted;
                }
                // 获取资源失败
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    interrupted = true;
            }
        } finally {
            // 如果没有成功获取资源
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * 以独占中断模式 acquire。
     * @param arg the acquire argument
     */
    private void doAcquireInterruptibly(int arg)
            throws InterruptedException {
        // 将当前线程以独占模式创建节点加入等待队列尾部
        final Node node = addWaiter(Node.EXCLUSIVE);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return;
                }
                // 不断自旋直到将前驱节点的状态设置为 SIGNAL，然后阻塞当前线程。
                // 将前驱节点状态设置成 SIGNAL 之后才能安心进入休眠状态。
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    // 如果 parkAndCheckInterrupt 返回 true 即 Thread.interrupted
                    // 返回 true 即线程被中断，则抛出中断异常。
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * 以独占限时模式 acquire。
     *
     * @param arg the acquire argument
     * @param nanosTimeout max wait time
     * @return {@code true} if acquired
     */
    private boolean doAcquireNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (nanosTimeout <= 0L)
            return false;
        // 计算截止时间
        final long deadline = System.nanoTime() + nanosTimeout;
        final Node node = addWaiter(Node.EXCLUSIVE);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return true;
                }
                // 计算剩余时间，如果达到截止时间立即返回 false
                nanosTimeout = deadline - System.nanoTime();
                if (nanosTimeout <= 0L)
                    return false;
                if (shouldParkAfterFailedAcquire(p, node) &&
                        nanosTimeout > spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if (Thread.interrupted())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * 共享不中断模式下执行 acquire。
     * @param arg the acquire argument
     */
    private void doAcquireShared(int arg) {
        // 将节点加入到队列尾部（节点为 SHARED 类型）
        final Node node = addWaiter(Node.SHARED);
        // 是否成功的标志
        boolean failed = true;
        try {
            // 等待过程中是否被中断过的标志
            boolean interrupted = false;
            // 自旋进入等待过程
            for (;;) {
                // p 保存其前驱节点
                final Node p = node.predecessor();
                // 如果节点某个时刻成为了 head 的后继节点，node 被 head 唤醒
                if (p == head) {
                    // 尝试获取资源
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        // 获取资源成功，将 head 指向自己，还有剩余资源可以唤醒
                        // 之后的线程
                        setHeadAndPropagate(node, r);
                        // 辅助回收承载该线程的节点 p
                        p.next = null; // help GC
                        // 如果在等待过程中被中断过，那么此时将中断补上
                        if (interrupted)
                            selfInterrupt();
                        // 改变 failed 标志位，表示获取成功，然后返回（退出此函数）
                        failed = false;
                        return;
                    }
                }
                // p 不是 head 的后继节点，则不能获取资源，寻找安全点，进入
                // waiting 状态，等待被 unpark 或 interrupt。
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    // 安心进入等待（中断）状态
                    interrupted = true;
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * 共享中断模式下 acquire。
     * @param arg the acquire argument
     */
    private void doAcquireSharedInterruptibly(int arg)
            throws InterruptedException {
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        failed = false;
                        return;
                    }
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * 以共享限时模式 acquire。
     *
     * @param arg the acquire argument
     * @param nanosTimeout max wait time
     * @return {@code true} if acquired
     */
    private boolean doAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (nanosTimeout <= 0L)
            return false;
        final long deadline = System.nanoTime() + nanosTimeout;
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        failed = false;
                        return true;
                    }
                }
                nanosTimeout = deadline - System.nanoTime();
                if (nanosTimeout <= 0L)
                    return false;
                if (shouldParkAfterFailedAcquire(p, node) &&
                        nanosTimeout > spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if (Thread.interrupted())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    // Main exported methods
    // 主要方法

    /**
     * 尝试以独占方式 acquire。此方法应该查询对象的状态是否允许以独占模式
     *  acquire，如果允许，则继续进行。
     *
     * 线程执行 acquire 操作时总是调用此方法。如果此方法提示失败，则线程进入
     * 等待队列，直到其他线程发出 release 的信号。这可以用来实现方法 tryLock。
     *
     * 默认的实现仅仅是抛出 UnsupportedOperationException 异常。需要由扩展了
     * AQS 的同步类来实现。
     *
     * 独占模式下只需要实现 tryAcquire 和 tryRelease，共享模式下只需要实现
     * tryAcquireShared 和 tryReleaseShared。
     *
     * @param arg the acquire argument. This value is always the one
     *        passed to an acquire method, or is the value saved on entry
     *        to a condition wait.  The value is otherwise uninterpreted
     *        and can represent anything you like.
     * @return {@code true} if successful. Upon success, this object has
     *         been acquired.
     * @throws IllegalMonitorStateException if acquiring would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     */
    protected boolean tryAcquire(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * 释放资源。
     * 如果已经释放掉资源返回 true，否则返回 false。
     *
     * @param arg the release argument. This value is always the one
     *        passed to a release method, or the current state value upon
     *        entry to a condition wait.  The value is otherwise
     *        uninterpreted and can represent anything you like.
     * @return {@code true} if this object is now in a fully released
     *         state, so that any waiting threads may attempt to acquire;
     *         and {@code false} otherwise.
     * @throws IllegalMonitorStateException if releasing would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     */
    protected boolean tryRelease(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * 尝试以共享模式 acquire。此方法应该查询对象的状态是否允许在共享模式
     * 下获取它，如果允许的话，才 acquire。
     *
     * 执行 acquire 的线程总是调用此方法。如果此方法报告失败，acquire 方法
     * 可能将线程入队列（如果它没有入队的话），直到通过其他线程的释放发出
     * 信号（signal）。
     *
     * 默认的实现抛出 UnsupportedOperationException 异常。
     *
     * @param arg the acquire argument. This value is always the one
     *        passed to an acquire method, or is the value saved on entry
     *        to a condition wait.  The value is otherwise uninterpreted
     *        and can represent anything you like.
     * @return a negative value on failure; zero if acquisition in shared
     *         mode succeeded but no subsequent shared-mode acquire can
     *         succeed; and a positive value if acquisition in shared
     *         mode succeeded and subsequent shared-mode acquires might
     *         also succeed, in which case a subsequent waiting thread
     *         must check availability. (Support for three different
     *         return values enables this method to be used in contexts
     *         where acquires only sometimes act exclusively.)  Upon
     *         success, this object has been acquired.
     * @throws IllegalMonitorStateException if acquiring would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     */
    protected int tryAcquireShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * 共享模式下释放资源。
     *
     * @param arg the release argument. This value is always the one
     *        passed to a release method, or the current state value upon
     *        entry to a condition wait.  The value is otherwise
     *        uninterpreted and can represent anything you like.
     * @return {@code true} if this release of shared mode may permit a
     *         waiting acquire (shared or exclusive) to succeed; and
     *         {@code false} otherwise
     * @throws IllegalMonitorStateException if releasing would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     */
    protected boolean tryReleaseShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * 如果同步状态由当前线程独占则返回 true。此方法在每次调用非等待的
     * ConditionObject 方法时调用。（相反等待方法时调用 release）。
     *
     * 如果不使用 condition 则此方法不需要定义。
     *
     * @return {@code true} if synchronization is held exclusively;
     *         {@code false} otherwise
     * @throws UnsupportedOperationException if conditions are not supported
     */
    protected boolean isHeldExclusively() {
        throw new UnsupportedOperationException();
    }

    /**
     * 以独占模式 acquire，忽略中断。通过调用一次或多次 tryAcquire 来实现，
     * 成功后返回。否则线程将入队列，可能会重复阻塞或者取消阻塞，直到
     * tryAcquire 成功。此方法可以用来实现 Lock.lock 方法。
     *
     * 此方法流程如下：
     * tryAcquire 尝试获取资源，如果成功直接返回；
     * addWaiter 将该线程加入到等待队列尾部，并且标记为独占模式；
     * acquireQueued 使线程在等待队列中获取资源，直到取到为止。在整个等待
     * 过程中被中断过返回 true，否则返回 false；
     * 如果线程在等待过程中被中断，它不会响应。直到获取到资源后才进行自我
     * 中断（selfInterrupt），将中断补上。
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     */
    public final void acquire(int arg) {
        if (!tryAcquire(arg) &&
                acquireQueued(addWaiter(Node.EXCLUSIVE), arg))
            selfInterrupt();
    }

    /**
     * 以独占模式 acquire，如果中断则中止。
     * 首先检查中断状态，然后至少调用一次 tryAcquire，成功直接返回。否则线程
     * 进入队列等待，可能重复阻塞或者取消阻塞，调用 tryAcquire 直到成功或线程
     * 被中断。此方法可用来实现 lockInterruptibly。
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @throws InterruptedException if the current thread is interrupted
     */
    public final void acquireInterruptibly(int arg)
            throws InterruptedException {
        // 中断则抛出异常
        if (Thread.interrupted())
            throw new InterruptedException();
        if (!tryAcquire(arg))
            doAcquireInterruptibly(arg);
    }

    /**
     * 尝试以独占模式 acquire，如果中断将中止，如果超出给定时间将失败。首先检查
     * 中断状态，然后至少调用一次 tryAcquire，成功立即返回。否则线程进入等待队列，
     * 可能会重复阻塞或取消阻塞，调用 tryAcquire 直到成功或线程中断或超时。
     * 此方法可用于实现 tryLock(long, TimeUnit)。
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @param nanosTimeout the maximum number of nanoseconds to wait
     * @return {@code true} if acquired; {@code false} if timed out
     * @throws InterruptedException if the current thread is interrupted
     */
    public final boolean tryAcquireNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        return tryAcquire(arg) ||
                doAcquireNanos(arg, nanosTimeout);
    }

    /**
     * 独占模式下 release。如果 tryRelease 返回 true，则通过解除一个或多个
     * 线程的阻塞来实现。此方法可以用来实现 Lock 的 unlock 方法。
     *
     * @param arg the release argument.  This value is conveyed to
     *        {@link #tryRelease} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @return the value returned from {@link #tryRelease}
     */
    public final boolean release(int arg) {
        // 通过 tryRelease 的返回值来判断是否已经完成释放资源
        if (tryRelease(arg)) {
            Node h = head;
            if (h != null && h.waitStatus != 0)
                // 唤醒等待队列里的下一个线程
                unparkSuccessor(h);
            return true;
        }
        return false;
    }

    /**
     * 忽略中断，以共享模式 acquire。首先调用至少一次 tryAcquireShared，
     * 成功后返回。否则线程将排队，可能会重复阻塞和取消阻塞，不断调用
     * tryAcquiredShared 直到成功。
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquireShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     */
    public final void acquireShared(int arg) {
        if (tryAcquireShared(arg) < 0)
            doAcquireShared(arg);
    }

    /**
     * 以共享模式 acquire，如果中断将中止。首先检查中断状态，然后调用至少一次
     * tryAcquireShared，成功立即返回。否则，想成进入等待队列，可能会重复阻塞
     * 和取消阻塞，调用 tryAcquireShared 直到成功或者线程中断。
     * @param arg the acquire argument.
     * This value is conveyed to {@link #tryAcquireShared} but is
     * otherwise uninterpreted and can represent anything
     * you like.
     * @throws InterruptedException if the current thread is interrupted
     */
    public final void acquireSharedInterruptibly(int arg)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        if (tryAcquireShared(arg) < 0)
            doAcquireSharedInterruptibly(arg);
    }

    /**
     * 尝试以共享模式 acquire，如果中断将停止，如果超过时限将失败。首先检查
     * 中断状态，然后至少调用一次 tryAcquireShared，成功立即返回。否则线程将
     * 进入等待队列，可能会重复阻塞和取消阻塞，调用 tryAcquireShared 直到成功
     * 或线程中断或超时。
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquireShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     * @param nanosTimeout the maximum number of nanoseconds to wait
     * @return {@code true} if acquired; {@code false} if timed out
     * @throws InterruptedException if the current thread is interrupted
     */
    public final boolean tryAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        return tryAcquireShared(arg) >= 0 ||
                doAcquireSharedNanos(arg, nanosTimeout);
    }

    /**
     * 共享模式下的 release 操作。如果 tryReleaseShared 返回 true，唤醒一个
     * 或多个线程。
     *
     * @param arg the release argument.  This value is conveyed to
     *        {@link #tryReleaseShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     * @return the value returned from {@link #tryReleaseShared}
     */
    public final boolean releaseShared(int arg) {
        // 尝试释放资源
        if (tryReleaseShared(arg)) {
            // 唤醒后继节点
            doReleaseShared();
            return true;
        }
        return false;
    }

    // Queue inspection methods
    // 队列检查方法

    /**
     * 查询是否有线程等待 acquire。由于任何时间的中断和时限到期将导致线程
     * 取消，返回 true 并不能保证任何其他线程 acquire。
     *
     * <p>In this implementation, this operation returns in
     * constant time.
     *
     * @return {@code true} if there may be other threads waiting to acquire
     */
    public final boolean hasQueuedThreads() {
        return head != tail;
    }

    /**
     * 查询是否有任何线程竞争过此同步器；即是否有 acquire 方法阻塞了。
     *
     * 此实现在常数时间内完成。
     * <p>In this implementation, this operation returns in
     * constant time.
     *
     * @return {@code true} if there has ever been contention
     */
    public final boolean hasContended() {
        return head != null;
    }

    /**
     * 返回队列中第一个线程（等待时间最久的线程），如果队列中没有线程
     * 返回 null。
     *
     * 此实现通常在常数时间内返回，但是如果其他线程同时修改队列，会在竞争
     * 的基础上迭代。
     *
     * @return the first (longest-waiting) thread in the queue, or
     *         {@code null} if no threads are currently queued
     */
    public final Thread getFirstQueuedThread() {
        // handle only fast path, else relay
        return (head == tail) ? null : fullGetFirstQueuedThread();
    }

    /**
     * Version of getFirstQueuedThread called when fastpath fails
     */
    private Thread fullGetFirstQueuedThread() {
        /**
         * 第一个节点通常是 head.next。尝试获取它的线程字段，确保一致性读取：
         * 如果线程字段为 null 或者 s 不再是 head，其他线程并发调用 setHead
         * 在两次读取之间。我们在遍历之前尝试两次。
         */
        Node h, s;
        Thread st;
        if (((h = head) != null && (s = h.next) != null &&
                s.prev == head && (st = s.thread) != null) ||
                ((h = head) != null && (s = h.next) != null &&
                        s.prev == head && (st = s.thread) != null))
            return st;

        /**
         * 运行到这一步表示 head.next 还没有设置，或者在执行 setHead 之后仍然
         * 没有设置。所以我们必须检查是否 tail 是第一个节点。如果不是，从 tail
         * 节点向 head 遍历直到找到第一个有效节点。
         */

        Node t = tail;
        Thread firstThread = null;
        while (t != null && t != head) {
            Thread tt = t.thread;
            if (tt != null)
                firstThread = tt;
            t = t.prev;
        }
        return firstThread;
    }

    /**
     * 如果指定的线程正在排队即返回 true。
     *
     * 此实现通过遍历队列来查找指定线程。（从 tail 到 head）
     *
     * @param thread the thread
     * @return {@code true} if the given thread is on the queue
     * @throws NullPointerException if the thread is null
     */
    public final boolean isQueued(Thread thread) {
        if (thread == null)
            throw new NullPointerException();
        for (Node p = tail; p != null; p = p.prev)
            if (p.thread == thread)
                return true;
        return false;
    }

    /**
     * 如果第一个显式排队的线程正在以独占模式等待，返回 true。如果此方法
     * 返回 true，且当前线程正在试图以共享模式 acquire（即在
     * tryAcquireShared 方法中调用），那么当前线程不是队列中第一个线程。
     * 在 ReetrantReadWriteLock 用于启发（？）
     */
    final boolean apparentlyFirstQueuedIsExclusive() {
        Node h, s;
        return (h = head) != null &&
                (s = h.next)  != null &&
                !s.isShared()         &&
                s.thread != null;
    }

    /**
     * 查询是否有任何线程比当前线程等待执行 acquire 的时间还长。
     *
     * 此方法的调用等价于（但是可能更有效）：
     * getFirstQueuedThread() != Thread.currentThread() && hasQueuedThreads()
     *
     * 注意，由于中断和超时导致的取消可能随时发生，返回 true 并不能保证
     * 其他线程在此线程会 acuqire。同样，在此方法返回 false 之后，由于队列
     * 为空，其他线程也可能成功入队。
     *
     * 此方法用来作为一个公平的同步器，以避免碰撞。这样的一个同步器的
     * tryAcquire 方法应该返回 false，如果此方法返回 true（除非这是一个
     * 可重入的 acquire），它的 tryAcquireShared 方法应该返回一个负值。
     * 例如，一个公平的，可重入的，独占模式同步器的 tryAcquire 方法应该
     * 看起来类似于：
     * protected boolean tryAcquire(int arg) {
     *   if (isHeldExclusively()) {
     *     // A reentrant acquire; increment hold count
     *     return true;
     *   } else if (hasQueuedPredecessors()) {
     *     return false;
     *   } else {
     *     // try to acquire normally
     *   }
     * }}
     *
     * @return {@code true} if there is a queued thread preceding the
     *         current thread, and {@code false} if the current thread
     *         is at the head of the queue or the queue is empty
     * @since 1.7
     */
    public final boolean hasQueuedPredecessors() {
        // The correctness of this depends on head being initialized
        // before tail and on head.next being accurate if the current
        // thread is first in queue.
        Node t = tail; // Read fields in reverse initialization order
        Node h = head;
        Node s;
        return h != t &&
                ((s = h.next) == null || s.thread != Thread.currentThread());
    }


    // Instrumentation and monitoring methods

    /**
     * 返回等待 acquire 线程数量的估计值。这个值只是一个估计值，因为当这个
     * 方法遍历内部数据结构的时候线程数可能会动态变化。此方法用于监控系统
     * 状态，不用于同步控制。
     *
     * 注意此类中几乎所有的遍历，都是从 tail 向 head 遍历。
     *
     * @return the estimated number of threads waiting to acquire
     */
    public final int getQueueLength() {
        int n = 0;
        for (Node p = tail; p != null; p = p.prev) {
            if (p.thread != null)
                ++n;
        }
        return n;
    }

    /**
     * 返回一个包含正在等待 acquire 的所有线程的集合。因为在构造这个结果的
     * 时候，实际的线程集合可能会动态变化，返回的集合只是一个最佳效果的
     * 估计。返回集合的元素没有特定的顺序。此方法用于构建更广泛监视工具。
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            Thread t = p.thread;
            if (t != null)
                list.add(t);
        }
        return list;
    }

    /**
     * 返回一个包含以独占模式等待 acquire 的线程集合。它具有和 getQueuedThreads
     * 相同的属性，只是它只返回独占模式等待的线程。
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getExclusiveQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (!p.isShared()) {
                Thread t = p.thread;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * 返回一个包含以共享模式等待 acquire 的线程集合。它具有和 getQueuedThreads
     * 相同的属性，只是它只返回独占模式等待的线程。
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getSharedQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (p.isShared()) {
                Thread t = p.thread;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * 返回能识别此同步器和其状态的字符串。
     *
     * @return a string identifying this synchronizer, as well as its state
     */
    public String toString() {
        int s = getState();
        String q  = hasQueuedThreads() ? "non" : "";
        return super.toString() +
                "[State = " + s + ", " + q + "empty queue]";
    }


    // Internal support methods for Conditions
    // Condition 的内部支撑方法

    /**
     * 如果一个节点现在正在同步队列上等待重新 acquire，则返回 true。
     * @param node the node
     * @return true if is reacquiring
     */
    final boolean isOnSyncQueue(Node node) {
        if (node.waitStatus == Node.CONDITION || node.prev == null)
            return false;
        if (node.next != null) // If has successor, it must be on queue
            return true;
        /**
         * node.prev 可以是非空的，但还不能放在队列上，因为将它放在队列上
         * 的 CAS 可能会失败。所以我们必须从 tail 开始遍历确保它确实成功了。
         * 在对这个方法的调用中，它总是在尾部附近，除非 CAS 失败（这是不太
         * 可能的），否则它将永远在那里，因此我们几乎不会遍历太多。
         */
        return findNodeFromTail(node);
    }

    /**
     * 从 tail 开始向前遍历，如果 node 在同步队列上返回 true。
     * 只在 isOnSyncQueue 方法中才会调用此方法。
     * @return true if present
     */
    private boolean findNodeFromTail(Node node) {
        Node t = tail;
        for (;;) {
            if (t == node)
                return true;
            if (t == null)
                return false;
            t = t.prev;
        }
    }

    /**
     * 把 condition 队列中的节点移动到 sync 队列中。
     * 如果成功返回 true。
     * @param node the node
     * @return true if successfully transferred (else the node was
     * cancelled before signal)
     */
    final boolean transferForSignal(Node node) {
        /**
         * 如果不能改变状态，说明节点已经被取消了。
         */
        if (!compareAndSetWaitStatus(node, Node.CONDITION, 0))
            return false;

        /**
         * 移动到 sync 队列中，并设置前驱节点的状态来表明线程正在等待。
         * 如果取消或者尝试设置状态失败，则唤醒并重新同步（在这种情况下，
         * 等待状态可能暂时错误，但不会造成任何伤害）。
         */
        Node p = enq(node);
        int ws = p.waitStatus;
        // 如果该节点的状态为 cancel 或者修改状态失败，则直接唤醒
        if (ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL))
            LockSupport.unpark(node.thread);
        return true;
    }

    /**
     * 传输节点，如果需要的话，在取消等待后同步队列。
     * 如果线程在被通知之后取消则返回 true。
     *
     * @param node the node
     * @return true if cancelled before the node was signalled
     */
    final boolean transferAfterCancelledWait(Node node) {
        if (compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
            enq(node);
            return true;
        }
        /*
         * If we lost out to a signal(), then we can't proceed
         * until it finishes its enq().  Cancelling during an
         * incomplete transfer is both rare and transient, so just
         * spin.
         */
        while (!isOnSyncQueue(node))
            // 从运行状态转换到就绪状态（让出时间片）
            Thread.yield();
        return false;
    }

    /**
     * 使用当前状态值调用 release；返回保存的状态。
     * 取消节点并在失败时抛出异常。
     * @param node the condition node for this wait
     * @return previous sync state
     */
    final int fullyRelease(Node node) {
        boolean failed = true;
        try {
            int savedState = getState();
            if (release(savedState)) {
                failed = false;
                return savedState;
            } else {
                throw new IllegalMonitorStateException();
            }
        } finally {
            if (failed)
                node.waitStatus = Node.CANCELLED;
        }
    }

    // Instrumentation methods for conditions
    // 监测 Condition 队列的方法

    /**
     * 检查给定 ConditionObject 是否使用此同步器作为其 lock。
     *
     * @param condition the condition
     * @return {@code true} if owned
     * @throws NullPointerException if the condition is null
     */
    public final boolean owns(ConditionObject condition) {
        return condition.isOwnedBy(this);
    }

    /**
     * 检查是否有线程等待在和此同步器关联的给定 condition 上。注意由于时间
     * 到期和线程中断会在任何时候发生，返回 true 并不保证未来的信号会唤醒
     * 任何线程。此方法用于监控系统状态。
     *
     * @param condition the condition
     * @return {@code true} if there are any waiting threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final boolean hasWaiters(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.hasWaiters();
    }

    /**
     * 返回与此同步器关联的 condition 上等待的线程数。注意由于时间到期和
     * 线程中断会在任何时候发生，因此估计值仅仅作为实际等待着数量的上限。
     * 此方法用于监视系统状态，而不是作为同步器控制。
     *
     * @param condition the condition
     * @return the estimated number of waiting threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final int getWaitQueueLength(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitQueueLength();
    }

    /**
     * 返回一个包含与此同步器关联的 condition 上所有线程的集合。由于构建此
     * 集合的时候，实际线程集合可能会动态变化，返回的集合只是一个最佳的
     * 估计。返回集合的元素没有特定顺序。
     *
     * @param condition the condition
     * @return the collection of threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final Collection<Thread> getWaitingThreads(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitingThreads();
    }

    /**
     * Condition 的实现
     */
    public class ConditionObject implements Condition, java.io.Serializable {
        private static final long serialVersionUID = 1173984872572414699L;
        /** Condition 队列的第一个节点 */
        private transient Node firstWaiter;
        /** Condition 队列的最后一个节点 */
        private transient Node lastWaiter;

        /**
         * 构造函数
         */
        public ConditionObject() { }

        // Internal methods
        // 内部方法

        /**
         * 在等待队列中添加一个新的 waiter 节点。
         * @return its new wait node
         */
        private Node addConditionWaiter() {
            Node t = lastWaiter;

            if (t != null && t.waitStatus != Node.CONDITION) {
                // 遍历链表，清除状态不是 CONDITION 的节点
                unlinkCancelledWaiters();
                t = lastWaiter;
            }
            // 创建包含当前线程的新的节点
            Node node = new Node(Thread.currentThread(), Node.CONDITION);
            if (t == null)
                firstWaiter = node;
            else
                t.nextWaiter = node;
            lastWaiter = node;
            return node;
        }

        /**
         * 删除和转变节点，直到命中一个并未取消或者为 null 的节点。
         * @param first (non-null) the first node on condition queue
         */
        private void doSignal(Node first) {
            do {
                // 设置新的头结点，并将节点从 condition 等待队列中移除
                if ( (firstWaiter = first.nextWaiter) == null)
                    lastWaiter = null;
                first.nextWaiter = null;
                // 将该节点加入到 sync 队列或者 condition 等待队列为空时跳出循环
            } while (!transferForSignal(first) &&
                    (first = firstWaiter) != null);
        }

        /**
         * 删除（删除指的是移出 condition 队列而不是垃圾回收）并转变所有节点。
         * @param first (non-null) the first node on condition queue
         */
        private void doSignalAll(Node first) {
            lastWaiter = firstWaiter = null;
            do {
                Node next = first.nextWaiter;
                // 将 first 节点移出 condition 队列
                first.nextWaiter = null;
                // 唤醒
                transferForSignal(first);
                first = next;
            } while (first != null);
        }

        /**
         * 从 condition 队列中删除已取消（状态不是 CONDITION 即为已取消）
         * 的等待节点。
         * 只有在持有锁的时候才调用。在 condition 队列中等待时如果发生节点
         * 取消，且看到 lastWaiter 被取消然后插入新节点时调用。
         * （addConditionWaiter 函数中调用）。需要使用此方法在没有 signal 的
         * 时候避免保留垃圾。因此即使它需要完整的遍历，也只有在没有信号的
         * 情况下发生超时或者取消时，它才会起作用。它将会遍历所有节点，而不是
         * 停在一个特定的目标上来取消垃圾节点的连接，且不需要在取消频繁发生时
         * 进行多次重复遍历。
         */
        private void unlinkCancelledWaiters() {
            Node t = firstWaiter;
            Node trail = null;
            // condition 队列不为空，从头结点开始遍历
            while (t != null) {
                Node next = t.nextWaiter;
                // 从 condition 中删除节点 t
                if (t.waitStatus != Node.CONDITION) {
                    t.nextWaiter = null;
                    if (trail == null)
                        firstWaiter = next;
                    else
                        trail.nextWaiter = next;
                    if (next == null)
                        lastWaiter = trail;
                }
                else
                    trail = t;
                // t 移向后一个节点，然后继续循环
                t = next;
            }
        }

        // public methods

        /**
         * 将最长等待的线程，（第一个节点）如果存在的话，从 condition 等待
         * 队列移动到拥有锁的等待队列。
         *
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        public final void signal() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            Node first = firstWaiter;
            if (first != null)
                doSignal(first);
        }

        /**
         * 将 condition 中等待的所有线程移动到拥有锁的等待队列。
         *
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        public final void signalAll() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            Node first = firstWaiter;
            if (first != null)
                doSignalAll(first);
        }

        /**
         * 实现不中断的 condition 队列上等待。
         * <ol>
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * </ol>
         */
        public final void awaitUninterruptibly() {
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            boolean interrupted = false;
            while (!isOnSyncQueue(node)) {
                LockSupport.park(this);
                // 如果线程中断，用标志位 interrupted 记录
                if (Thread.interrupted())
                    interrupted = true;
            }
            // 等到进入 sync 队列，如果成功 acquire 或者标志位显示经历了中断
            // 过程，则自行中断。
            if (acquireQueued(node, savedState) || interrupted)
                selfInterrupt();
        }

        /**
         * 对于可中断的等待，我们需要跟踪如果阻塞在 condition 中的时候，
         * 是否抛出 InterruptedException，如果在阻塞中中断等待重新获取时，
         * 是否重新中断当前线程。
         */

        /** 退出等待状态时重新中断 */
        private static final int REINTERRUPT =  1;
        /** 退出等待状态时抛出 InterruptException */
        private static final int THROW_IE    = -1;

        /**
         * 检查中断，如果中断发生在 signal 之前则返回 THROW_IE，如果在 signal
         * 之后则返回 REINTERRUPT，如果没有发生返回 0.
         */
        private int checkInterruptWhileWaiting(Node node) {
            return Thread.interrupted() ?
                    (transferAfterCancelledWait(node) ? THROW_IE : REINTERRUPT) :
                    0;
        }

        /**
         * 抛出 InterruptedException，再次中断当前线程，或者不做任何操作，
         * 取决于具体的模式。
         */
        private void reportInterruptAfterWait(int interruptMode)
                throws InterruptedException {
            if (interruptMode == THROW_IE)
                throw new InterruptedException();
            else if (interruptMode == REINTERRUPT)
                selfInterrupt();
        }

        /**
         * 实现可中断 condition 的 wait 方法。
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled or interrupted.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * </ol>
         */
        public final void await() throws InterruptedException {
            // 如果当前线程被中断，抛出 InterruptedException 异常。
            if (Thread.interrupted())
                throw new InterruptedException();
            // 创建和此线程关联的节点，将其加入到 condition 队列中
            Node node = addConditionWaiter();
            // 释放当前线程并返回释放前的状态值
            int savedState = fullyRelease(node);
            int interruptMode = 0;
            // 如果节点不在 sync 中一直循环（阻塞）
            // 同时检查是否发生中断，如果发生则中止循环
            while (!isOnSyncQueue(node)) {
                LockSupport.park(this);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
            }
            // 被唤醒后，重新加入到同步队列队尾竞争获取锁，如果竞争不到则会沉睡，等待唤醒重新开始竞争。
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null) // clean up if cancelled
                unlinkCancelledWaiters();
            // 如果在之前的 while 循环中有中断发生，抛出 InterruptedException 异常
            // 延迟响应中断
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
        }

        /**
         * 实现 condition 里有时间限制的 wait 方法。
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * </ol>
         */
        public final long awaitNanos(long nanosTimeout)
                throws InterruptedException {
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            final long deadline = System.nanoTime() + nanosTimeout;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (nanosTimeout <= 0L) {
                    transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
                nanosTimeout = deadline - System.nanoTime();
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return deadline - System.nanoTime();
        }

        /**
         * 实现指定截止时间的 condition 中 wait 方法。
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * <li> If timed out while blocked in step 4, return false, else true.
         * </ol>
         */
        public final boolean awaitUntil(Date deadline)
                throws InterruptedException {
            long abstime = deadline.getTime();
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (System.currentTimeMillis() > abstime) {
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                LockSupport.parkUntil(this, abstime);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return !timedout;
        }

        /**
         * Implements timed condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * <li> If timed out while blocked in step 4, return false, else true.
         * </ol>
         */
        public final boolean await(long time, TimeUnit unit)
                throws InterruptedException {
            long nanosTimeout = unit.toNanos(time);
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            final long deadline = System.nanoTime() + nanosTimeout;
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (nanosTimeout <= 0L) {
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
                nanosTimeout = deadline - System.nanoTime();
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return !timedout;
        }

        //  support for instrumentation

        /**
         * 如果 condition 由给定同步器创建返回 true。
         *
         * @return {@code true} if owned
         */
        final boolean isOwnedBy(AbstractQueuedSynchronizer sync) {
            return sync == AbstractQueuedSynchronizer.this;
        }

        /**
         * 查询是否有线程在 condition 中等待。
         *
         * @return {@code true} if there are any waiting threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        protected final boolean hasWaiters() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            // 遍历所有节点
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    return true;
            }
            return false;
        }

        /**
         * 返回在 condition 中等待的线程数估计值。
         * Implements {@link AbstractQueuedSynchronizer#getWaitQueueLength(ConditionObject)}.
         *
         * @return the estimated number of waiting threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        protected final int getWaitQueueLength() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            int n = 0;
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    ++n;
            }
            return n;
        }

        /**
         * 返回在 condition 上等待的所有线程的集合。
         *
         * @return the collection of threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        protected final Collection<Thread> getWaitingThreads() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            ArrayList<Thread> list = new ArrayList<Thread>();
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION) {
                    Thread t = w.thread;
                    if (t != null)
                        list.add(t);
                }
            }
            return list;
        }
    }

    /**
     * Setup to support compareAndSet. We need to natively implement
     * this here: For the sake of permitting future enhancements, we
     * cannot explicitly subclass AtomicInteger, which would be
     * efficient and useful otherwise. So, as the lesser of evils, we
     * natively implement using hotspot intrinsics API. And while we
     * are at it, we do the same for other CASable fields (which could
     * otherwise be done with atomic field updaters).
     */
    private static final Unsafe unsafe = Unsafe.getUnsafe();
    private static final long stateOffset;
    private static final long headOffset;
    private static final long tailOffset;
    private static final long waitStatusOffset;
    private static final long nextOffset;

    static {
        try {
            stateOffset = unsafe.objectFieldOffset
                    (AbstractQueuedSynchronizer.class.getDeclaredField("state"));
            headOffset = unsafe.objectFieldOffset
                    (AbstractQueuedSynchronizer.class.getDeclaredField("head"));
            tailOffset = unsafe.objectFieldOffset
                    (AbstractQueuedSynchronizer.class.getDeclaredField("tail"));
            waitStatusOffset = unsafe.objectFieldOffset
                    (Node.class.getDeclaredField("waitStatus"));
            nextOffset = unsafe.objectFieldOffset
                    (Node.class.getDeclaredField("next"));

        } catch (Exception ex) { throw new Error(ex); }
    }

    /**
     * CAS head field. Used only by enq.
     */
    private final boolean compareAndSetHead(Node update) {
        return unsafe.compareAndSwapObject(this, headOffset, null, update);
    }

    /**
     * CAS tail field. Used only by enq.
     */
    private final boolean compareAndSetTail(Node expect, Node update) {
        return unsafe.compareAndSwapObject(this, tailOffset, expect, update);
    }

    /**
     * CAS waitStatus field of a node.
     */
    private static final boolean compareAndSetWaitStatus(Node node,
                                                         int expect,
                                                         int update) {
        return unsafe.compareAndSwapInt(node, waitStatusOffset,
                expect, update);
    }

    /**
     * CAS next field of a node.
     */
    private static final boolean compareAndSetNext(Node node,
                                                   Node expect,
                                                   Node update) {
        return unsafe.compareAndSwapObject(node, nextOffset, expect, update);
    }
}

