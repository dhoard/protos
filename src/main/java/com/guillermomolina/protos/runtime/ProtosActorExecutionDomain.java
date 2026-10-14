/*
 * THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
 * ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. ANY USE, PUBLIC
 * DISPLAY, PUBLIC PERFORMANCE, REPRODUCTION OR DISTRIBUTION OF, OR PREPARATION OF
 * DERIVATIVE WORKS BASED ON, THE LICENSED WORK CONSTITUTES RECIPIENT'S ACCEPTANCE
 * OF THIS LICENSE AND ITS TERMS, WHETHER OR NOT SUCH RECIPIENT READS THE TERMS OF
 * THE LICENSE. "LICENSED WORK" AND "RECIPIENT" ARE DEFINED IN THE LICENSE. A COPY
 * OF THE LICENSE IS LOCATED IN THE TEXT FILE ENTITLED "LICENSE.TXT" ACCOMPANYING
 * THE CONTENTS OF THIS FILE. IF A COPY OF THE LICENSE DOES NOT ACCOMPANY THIS
 * FILE, A COPY OF THE LICENSE MAY ALSO BE OBTAINED AT THE FOLLOWING WEB SITE:
 * https://github.com/guillermomolina/protos
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 */
package com.guillermomolina.protos.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Actor-local cooperative scheduling domain.
 *
 * <p>The ordinary runnable queue is FIFO for this implementation. FIFO is an implementation
 * policy, not a new language-level total ordering guarantee; it also satisfies weak fairness for
 * continuously runnable tasks when dispatch continues. Targeted runtime-completion controls are
 * Actor-private machinery and never change the relative FIFO order of unrelated ordinary runnable
 * work.
 */
public final class ProtosActorExecutionDomain {
    private static final Runnable NOOP_WAKEUP = () -> {};
    private static final Runnable NOOP_DISCARD = () -> {};

    private static final class TargetedRuntimeCompletion {
        private final ProtosFutureValue future;
        private final BooleanSupplier completion;
        private final Runnable onDiscard;

        private TargetedRuntimeCompletion(
                ProtosFutureValue future,
                BooleanSupplier completion,
                Runnable onDiscard) {
            this.future = Objects.requireNonNull(future, "future");
            this.completion = Objects.requireNonNull(completion, "completion");
            this.onDiscard = Objects.requireNonNull(onDiscard, "onDiscard");
        }
    }

    private final ArrayDeque<Object> runnable = new ArrayDeque<>();
    private boolean actorTerminationBegun;
    private final ArrayDeque<TargetedRuntimeCompletion> targetedRuntimeCompletions =
            new ArrayDeque<>();
    /*
     * PERF025-G2: Task is backend-private runtime identity and live-task iteration order is not a
     * Protos semantic. Avoid one linked-set entry node per live Task while retaining exact Task
     * enumeration for Actor TERMINATING cancellation.
     */
    private final Set<ProtosTask> liveTasks =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ProtosIoOperation> actorIoOperations = new LinkedHashSet<>();
    private final Set<ProtosIoReleaseExecution> actorIoReleases = new LinkedHashSet<>();
    private final Set<ProtosIoLifecycle> actorIoLifecycleCleanup = new LinkedHashSet<>();
    private final Set<ProtosFutureValue> actorNonTaskFutures = new LinkedHashSet<>();
    private ProtosActor ownerActor;
    private Runnable schedulerWakeup = NOOP_WAKEUP;
    /*
     * PLAT054-3E2: confined to the thread that holds the RootActor host entry (entry admission
     * publishes it between entries), so it needs no monitor.
     */
    private HostEntryExtent hostEntryExtent = HostEntryExtent.NONE;
    private int activeTargetedRuntimeCompletions;

    /**
     * PLAT054-3E2 ({@code FUTURES_AND_TASKS.md} §29, Suspendible host-initiated RootActor entries):
     * what the non-Task guest code currently executing in this domain may do at an explicit
     * suspension point. Task-owned execution never consults it.
     */
    public enum HostEntryExtent {
        /** No host entry governs the current execution; ordinary non-Task rules apply. */
        NONE,
        /**
         * Directly inside an outermost host entry with only Bytecode frames between the
         * suspension point and the entry, so a C-prime continuation can reach the entry's driver.
         */
        SUSPENDIBLE,
        /**
         * Inside a host entry but within a callback of a synchronous foreign operation (including
         * a nested host entry): an actual suspension is rejected with a fresh {@code Error}.
         */
        SYNCHRONOUS
    }

    public HostEntryExtent hostEntryExtentForRuntime() {
        return hostEntryExtent;
    }

    /** Installs {@code next} and returns the previous extent, which the caller must restore. */
    public HostEntryExtent swapHostEntryExtentForRuntime(HostEntryExtent next) {
        HostEntryExtent previous = hostEntryExtent;
        hostEntryExtent = Objects.requireNonNull(next, "next");
        return previous;
    }

    /** Wakes a host entry blocked in {@link #awaitHostEntryWakeupForRuntime}. */
    public synchronized void wakeHostEntryWaiterForRuntime() {
        notifyAll();
    }

    /**
     * Blocks the suspended host entry's thread until runnable Actor-local work exists or {@code
     * wake} holds. {@code wake} is evaluated under this monitor, and every source that can make it
     * true notifies this monitor after changing its state, so no wakeup is lost; a spurious return
     * is harmless because the caller re-checks.
     */
    public synchronized void awaitHostEntryWakeupForRuntime(BooleanSupplier wake)
            throws InterruptedException {
        if (runnable.isEmpty() && targetedRuntimeCompletions.isEmpty() && !wake.getAsBoolean()) {
            wait();
        }
    }

    public ProtosTask createTask(
            ProtosTask parent, Object associatedFuture, ProtosTask.Continuation continuation) {
        Objects.requireNonNull(continuation, "continuation");
        ProtosTask task = new ProtosTask(this, parent, associatedFuture, continuation);
        boolean cancelOnStart = registerNewTask(task, parent);
        enqueue(task);
        if (cancelOnStart) {
            task.requestCancellation();
        }
        return task;
    }

    /**
     * Registers a fresh parentless Task and runs its first segment directly on the calling
     * (Actor-owning) execution path, without the runnable-queue round-trip of
     * {@link #createTask(ProtosTask, Object, ProtosTask.Continuation)}.
     *
     * <p>Registration, ownership and TERMINATED rejection are identical to {@code createTask}. When
     * the Actor is already TERMINATING, cancellation is recorded before the first segment starts, so
     * {@link ProtosTask#runContinuation()} observes it at the first-execution boundary instead of
     * running the ordinary continuation. If the segment suspends or becomes runnable again, the
     * Task re-enters the ordinary queue exactly as a queue-dispatched Task would. The caller
     * continues with {@link #dispatchUntilTerminal} for any remaining work.
     */
    public ProtosTask runFreshRootTaskDirectly(ProtosTask.Continuation continuation) {
        Objects.requireNonNull(continuation, "continuation");
        ProtosTask task = new ProtosTask(this, null, null, continuation);
        boolean cancelOnStart = registerNewTask(task, null);
        if (cancelOnStart) {
            task.requestCancellation();
        }
        if (!task.beginDirectDispatch()) {
            throw new IllegalStateException("fresh root task could not begin direct dispatch");
        }
        task.runContinuation();
        return task;
    }

    /** @return whether the owning Actor is already TERMINATING, so the Task must start cancelled */
    private boolean registerNewTask(ProtosTask task, ProtosTask parent) {
        synchronized (this) {
            if (parent != null && parent.owner() != this) {
                throw new IllegalArgumentException("structured parent belongs to another Actor domain");
            }
            if (ownerActor != null
                    && ownerActor.lifecycleState() == ProtosActor.LifecycleState.TERMINATED) {
                throw new IllegalStateException("terminated Actor cannot create Actor-local tasks");
            }
            if (parent != null) {
                parent.addChild(task);
            }
            liveTasks.add(task);
            return ownerActor != null
                    && ownerActor.lifecycleState() == ProtosActor.LifecycleState.TERMINATING;
        }
    }

    public ProtosTask createTask(Object associatedFuture, ProtosTask.Continuation continuation) {
        return createTask(null, associatedFuture, continuation);
    }

    void enqueue(ProtosTask task) {
        Objects.requireNonNull(task, "task");
        Runnable wakeup = null;
        synchronized (this) {
            requireOwned(task);
            if (task.markQueued()) {
                runnable.addLast(task);
                notifyAll();
                wakeup = schedulerWakeup;
            }
        }
        if (wakeup != null) {
            runSchedulerWakeup(wakeup);
        }
    }

    /**
     * PERF019 runtime-only publication for one caller-domain logical-Case completion.
     *
     * <p>This is not a guest scheduling API. The completion runs only after this Actor obtains
     * execution ownership. It may nominate only the exact Future waiter made runnable by its own
     * winning terminal transition.
     */
    public void enqueueTargetedFutureCompletionForRuntime(
            ProtosFutureValue future,
            BooleanSupplier completion) {
        enqueueTargetedFutureCompletionForRuntime(
                future, completion, NOOP_DISCARD);
    }

    /**
     * Host-only cleanup when an Actor termination discards a completion.
     * onDiscard must never perform guest execution.
     */
    public void enqueueTargetedFutureCompletionForRuntime(
            ProtosFutureValue future,
            BooleanSupplier completion,
            Runnable onDiscard) {
        Objects.requireNonNull(future, "future");
        Objects.requireNonNull(completion, "completion");
        Objects.requireNonNull(onDiscard, "onDiscard");
        if (future.domain() != this) {
            throw new IllegalArgumentException(
                    "targeted Future completion belongs to another Actor domain");
        }

        Runnable wakeup;
        synchronized (this) {
            if (actorTerminationBegun
                    || (ownerActor != null
                    && (ownerActor.lifecycleState() == ProtosActor.LifecycleState.TERMINATING
                            || ownerActor.lifecycleState()
                                    == ProtosActor.LifecycleState.TERMINATED))) {
                throw new IllegalStateException(
                        "terminating Actor cannot accept targeted runtime completion");
            }
            targetedRuntimeCompletions.addLast(
                    new TargetedRuntimeCompletion(
                            future, completion, onDiscard));
            notifyAll();
            wakeup = schedulerWakeup;
        }
        runSchedulerWakeup(wakeup);
    }

    /** PLAT029 readiness publication for one already-registered Actor-local I/O operation. */
    void enqueueActorIoOperationForRuntime(ProtosIoOperation operation) {
        Objects.requireNonNull(operation, "operation");
        Runnable wakeup = null;
        synchronized (this) {
            if (!actorIoOperations.contains(operation)) {
                return;
            }
            runnable.addLast(operation);
            notifyAll();
            wakeup = schedulerWakeup;
        }
        if (wakeup != null) {
            runSchedulerWakeup(wakeup);
        }
    }

    /** PLAT030 readiness publication for one registered lifecycle-release C-prime owner. */
    public void enqueueActorIoReleaseForRuntime(ProtosIoReleaseExecution release) {
        Objects.requireNonNull(release, "release");
        Runnable wakeup = null;
        synchronized (this) {
            if (!actorIoReleases.contains(release)) return;
            runnable.addLast(release);
            notifyAll();
            wakeup = schedulerWakeup;
        }
        if (wakeup != null) runSchedulerWakeup(wakeup);
    }

    /**
     * Dispatches one Actor-owned scheduling unit.
     *
     * <p>A PERF019 runtime completion may directly hand off to the one exact suspended Task made
     * runnable by its own Future terminal transition. The runtime action itself is not guest
     * execution, and the handoff Task remains a distinct later Actor-local guest segment.
     */
    public boolean dispatchOne() {
        TargetedRuntimeCompletion runtimeCompletion;
        synchronized (this) {
            runtimeCompletion = targetedRuntimeCompletions.pollFirst();
            if (runtimeCompletion != null) {
                activeTargetedRuntimeCompletions++;
            }
        }

        if (runtimeCompletion != null) {
            try {
                dispatchTargetedRuntimeCompletion(runtimeCompletion);
            } finally {
                finishTargetedRuntimeCompletion();
            }
            return true;
        }

        Object scheduled;
        while (true) {
            synchronized (this) {
                scheduled = runnable.pollFirst();
                if (scheduled == null) {
                    return false;
                }
                if (scheduled instanceof ProtosTask task) {
                    if (!task.beginDispatch()) {
                        continue;
                    }
                } else if (!(scheduled instanceof ProtosIoOperation)
                        && !(scheduled instanceof ProtosIoReleaseExecution)) {
                    throw new IllegalStateException(
                            "Actor runnable queue contains an unsupported execution segment");
                }
            }
            if (scheduled instanceof ProtosIoOperation operation
                    && !operation.beginDeferredCPrimeDispatchForRuntime()) continue;
            if (scheduled instanceof ProtosIoReleaseExecution release
                    && !release.beginDeferredCPrimeDispatchForRuntime()) continue;
            break;
        }

        if (scheduled instanceof ProtosTask task) task.runContinuation();
        else if (scheduled instanceof ProtosIoOperation operation)
            operation.runDeferredCPrimeSegmentForRuntime();
        else ((ProtosIoReleaseExecution) scheduled).runDeferredCPrimeSegmentForRuntime();
        return true;
    }

    private void dispatchTargetedRuntimeCompletion(
            TargetedRuntimeCompletion runtimeCompletion) {
        ProtosFutureValue.RuntimeHandoffCandidate candidate =
                runtimeCompletion.future.targetedHandoffCandidateForRuntime();

        boolean transitioned = runtimeCompletion.completion.getAsBoolean();
        if (!transitioned || candidate == null || !candidate.readyForHandoff()) {
            return;
        }

        ProtosTask handoff = claimTargetedContinuation(candidate);
        if (handoff != null) {
            handoff.runContinuation();
        }
    }

    private ProtosTask claimTargetedContinuation(
            ProtosFutureValue.RuntimeHandoffCandidate candidate) {
        ProtosTask target = candidate.task();
        if (target.owner() != this) {
            return null;
        }

        synchronized (this) {
            Iterator<Object> iterator = runnable.iterator();
            while (iterator.hasNext()) {
                Object queued = iterator.next();
                if (queued != target) {
                    continue;
                }
                if (!target.beginTargetedRuntimeHandoff(candidate.dependency())) {
                    return null;
                }
                iterator.remove();
                return target;
            }
        }
        return null;
    }

    private void finishTargetedRuntimeCompletion() {
        ProtosActor actor;
        synchronized (this) {
            if (activeTargetedRuntimeCompletions <= 0) {
                throw new IllegalStateException(
                        "targeted runtime completion accounting underflow");
            }
            activeTargetedRuntimeCompletions--;
            notifyAll();
            actor = ownerActor;
        }
        if (actor != null) {
            actor.tryCompleteTerminationForRuntime();
        }
    }

    /** Starts one already-accepted mailbox message as the current Actor segment. */
    ProtosTask dispatchAcceptedTurn(ProtosTask.Continuation continuation) {
        Objects.requireNonNull(continuation, "continuation");
        ProtosTask task = new ProtosTask(this, null, null, continuation);
        synchronized (this) {
            liveTasks.add(task);
        }
        if (!task.beginDirectDispatch()) {
            throw new IllegalStateException("fresh mailbox task could not begin dispatch");
        }
        task.runContinuation();
        return task;
    }

    public void dispatchUntilIdle() {
        while (dispatchOne()) {
            // Cooperative segments themselves decide whether to suspend or terminate.
        }
    }

    public void dispatchUntilTerminal(ProtosTask root,java.util.function.BooleanSupplier helper) {
        Objects.requireNonNull(root);Objects.requireNonNull(helper);
        while(true){ProtosTask.State s=root.state();if(s==ProtosTask.State.COMPLETED||s==ProtosTask.State.FAILED||s==ProtosTask.State.CANCELLED)return;if(dispatchOne()||helper.getAsBoolean())continue;synchronized(this){s=root.state();if(s==ProtosTask.State.COMPLETED||s==ProtosTask.State.FAILED||s==ProtosTask.State.CANCELLED)return;if(!runnable.isEmpty()||!targetedRuntimeCompletions.isEmpty())continue;try{wait();}catch(InterruptedException e){Thread.currentThread().interrupt();root.requestCancellation();}}}
    }

    public synchronized int runnableCount() {
        return runnable.size() + targetedRuntimeCompletions.size();
    }

    synchronized boolean hasRunnableForRuntime() {
        return !runnable.isEmpty() || !targetedRuntimeCompletions.isEmpty();
    }

    public synchronized int liveTaskCount() {
        return liveTasks.size();
    }

    public synchronized Optional<ProtosTask> nextRunnableForTesting() {
        Object next = runnable.peekFirst();
        return next instanceof ProtosTask task ? Optional.of(task) : Optional.empty();
    }

    void terminal(ProtosTask task) {
        ProtosActor actor;
        synchronized (this) {
            requireOwned(task);
            liveTasks.remove(task);
            notifyAll();
            ProtosTask parent = task.parentForRuntime();
            if (parent != null) {
                parent.removeChild(task);
            }
            actor = ownerActor;
        }
        if (actor != null) {
            actor.tryCompleteTerminationForRuntime();
        }
    }

    void registerActorIoReleaseForRuntime(ProtosIoReleaseExecution release) {
        Objects.requireNonNull(release, "release");
        synchronized (this) {
            if (ownerActor != null && ownerActor.lifecycleState() == ProtosActor.LifecycleState.TERMINATED)
                throw new IllegalStateException("terminated Actor cannot acquire lifecycle-release guest execution");
            actorIoReleases.add(release);
        }
    }

    public void terminalActorIoReleaseForRuntime(ProtosIoReleaseExecution release) {
        ProtosActor actor;
        synchronized (this) { actorIoReleases.remove(release); notifyAll(); actor=ownerActor; }
        if (actor != null) actor.tryCompleteTerminationForRuntime();
    }

    void registerActorIoLifecycleCleanupForRuntime(ProtosIoLifecycle lifecycle) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        synchronized (this) {
            if (ownerActor != null && ownerActor.lifecycleState() == ProtosActor.LifecycleState.TERMINATED)
                throw new IllegalStateException("terminated Actor cannot acquire lifecycle cleanup obligation");
            actorIoLifecycleCleanup.add(lifecycle);
        }
    }

    void terminalActorIoLifecycleCleanupForRuntime(ProtosIoLifecycle lifecycle) {
        ProtosActor actor;
        synchronized (this) { actorIoLifecycleCleanup.remove(lifecycle); notifyAll(); actor=ownerActor; }
        if (actor != null) actor.tryCompleteTerminationForRuntime();
    }

    void registerActorIoOperation(ProtosIoOperation operation) {
        synchronized (this) { actorIoOperations.add(Objects.requireNonNull(operation, "operation")); }
    }

    void terminalActorIoOperation(ProtosIoOperation operation) {
        synchronized (this) { actorIoOperations.remove(operation); }
    }

    /** Includes Actor domains without a separately bound Actor. */
    public synchronized boolean actorTerminationBegunForRuntime() {
        return actorTerminationBegun;
    }

    /** TERMINATING cutover hook: request cooperative cancellation without undoing commitments. */
    public void actorTerminationBegun() {
        Set<ProtosTask> tasks;
        Set<ProtosIoOperation> io;
        Set<ProtosFutureValue> nonTask;
        java.util.List<TargetedRuntimeCompletion> dropped;
        synchronized (this) {
            actorTerminationBegun = true;
            dropped = java.util.List.copyOf(targetedRuntimeCompletions);
            targetedRuntimeCompletions.clear();
            notifyAll();
            tasks = Set.copyOf(liveTasks);
            io = Set.copyOf(actorIoOperations);
            nonTask = Set.copyOf(actorNonTaskFutures);
        }
        // Reconcile host custody before cancelling remaining operations.
        // Never run cleanup under the domain lock or execute guest code here.
        for (TargetedRuntimeCompletion completion : dropped) {
            try {
                completion.onDiscard.run();
            } catch (RuntimeException ignored) {
                // A failed cleanup cannot suppress the termination cutover.
            }
        }
        for (ProtosTask task : tasks) task.requestCancellation();
        for (ProtosIoOperation operation : io) operation.requestCancellation();
        for (ProtosFutureValue future : nonTask) future.cancelRequest();
    }

    /** Historical/internal compatibility hook; termination cancellation now starts at TERMINATING. */
    public void actorTerminated() {
        actorTerminationBegun();
    }

    /** Runtime/bootstrap hook for registering an ordinary pending non-task Future. */
    public void registerActorNonTaskFutureForRuntime(ProtosFutureValue future) {
        registerActorNonTaskFuture(future);
    }

    void registerActorNonTaskFuture(ProtosFutureValue future) {
        Objects.requireNonNull(future, "future");
        boolean cancelNow;
        synchronized (this) {
            if (!future.isPending()) return;
            actorNonTaskFutures.add(future);
            cancelNow = ownerActor != null
                    && (ownerActor.lifecycleState() == ProtosActor.LifecycleState.TERMINATING
                            || ownerActor.lifecycleState() == ProtosActor.LifecycleState.TERMINATED);
        }
        future.observe(ignored -> terminalActorNonTaskFuture(future));
        if (cancelNow) future.cancelRequest();
    }

    void terminalActorNonTaskFuture(ProtosFutureValue future) {
        synchronized (this) { actorNonTaskFutures.remove(future); }
    }

    synchronized boolean hasLiveTasksForRuntime() { return !liveTasks.isEmpty(); }
    synchronized boolean hasTerminationCleanupForRuntime() {
        return activeTargetedRuntimeCompletions != 0
                || !actorIoLifecycleCleanup.isEmpty()
                || !actorIoReleases.isEmpty();
    }
    synchronized int actorNonTaskFutureCountForTesting() { return actorNonTaskFutures.size(); }
    synchronized int actorIoOperationCountForTesting() { return actorIoOperations.size(); }
    synchronized int actorIoReleaseCountForTesting() { return actorIoReleases.size(); }
    synchronized int actorIoLifecycleCleanupCountForTesting() { return actorIoLifecycleCleanup.size(); }

    void bindActor(ProtosActor actor) {
        Objects.requireNonNull(actor, "actor");
        synchronized (this) {
            if (ownerActor != null && ownerActor != actor) {
                throw new IllegalStateException("execution domain already belongs to another Actor");
            }
            ownerActor = actor;
        }
    }

    void bindSchedulerWakeup(Runnable wakeup) {
        Objects.requireNonNull(wakeup, "wakeup");
        synchronized (this) {
            if (schedulerWakeup != NOOP_WAKEUP) {
                throw new IllegalStateException("execution domain is already attached to a scheduler");
            }
            schedulerWakeup = wakeup;
        }
    }

    /** Runtime owner lookup; Actor identity remains local to this execution domain. */
    public synchronized Optional<ProtosActor> currentActorForRuntime() {
        return Optional.ofNullable(ownerActor);
    }

    /** Runtime substrate for the future Actor.current() primitive; no global current Actor. */
    public synchronized Optional<ProtosActorRefValue> currentActorReference() {
        return ownerActor == null ? Optional.empty() : Optional.of(ownerActor.reference());
    }

    private void requireOwned(ProtosTask task) {
        if (task.owner() != this) {
            throw new IllegalArgumentException("task belongs to another Actor execution domain");
        }
    }

    @TruffleBoundary
    private static void runSchedulerWakeup(Runnable wakeup) {
        wakeup.run();
    }

}
