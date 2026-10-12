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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One concrete Actor incarnation.
 *
 * <p>The incarnation identity is fixed at construction time and is independent of lifecycle,
 * execution-domain, module-state, mailbox, placement, or host-thread identity.
 */
public final class ProtosActor {
    public enum LifecycleState {
        INITIALIZING,
        READY,
        TERMINATING,
        TERMINATED
    }

    private static final AtomicLong NEXT_INCARNATION_ID = new AtomicLong();
    private static final int DEFAULT_MAILBOX_CAPACITY = 256;

    private final long incarnationIdentity;
    private final ProtosActorExecutionDomain executionDomain;
    private final ProtosActorModuleState moduleState;
    private final ProtosProcessRuntime processRuntime;
    private final boolean rootActor;
    private final ProtosActorRefValue reference;
    private final ProtosActorMailbox mailbox;
    private final ProtosActorDeliveryAdmission deliveryAdmission;
    private ProtosPrelude messagePrelude;
    private ProtosModuleKey messageModuleKey;
    private final AtomicReference<LifecycleState> lifecycle =
            new AtomicReference<>(LifecycleState.INITIALIZING);
    private final AtomicReference<ProtosObjectValue> currentBehavior =
            new AtomicReference<>();
    private final Set<ProtosActorTerminationObservation> terminationObservers =
            new LinkedHashSet<>();
    private final Set<ProtosActorGroupRuntime> routingGroups = new LinkedHashSet<>();
    private boolean terminationCutoverCleanupComplete;

    public ProtosActor(ProtosObjectValue actorRefPrototype) {
        this(
                actorRefPrototype,
                new ProtosActorExecutionDomain(),
                new ProtosActorModuleState(),
                DEFAULT_MAILBOX_CAPACITY,
                null,
                false);
    }

    ProtosActor(
            ProtosObjectValue actorRefPrototype,
            ProtosActorExecutionDomain executionDomain,
            ProtosActorModuleState moduleState) {
        this(
                actorRefPrototype,
                executionDomain,
                moduleState,
                DEFAULT_MAILBOX_CAPACITY,
                null,
                false);
    }

    ProtosActor(
            ProtosObjectValue actorRefPrototype,
            ProtosProcessRuntime processRuntime,
            boolean rootActor) {
        this(
                actorRefPrototype,
                new ProtosActorExecutionDomain(),
                new ProtosActorModuleState(),
                DEFAULT_MAILBOX_CAPACITY,
                Objects.requireNonNull(processRuntime, "processRuntime"),
                rootActor);
    }

    ProtosActor(
            ProtosObjectValue actorRefPrototype,
            ProtosActorExecutionDomain executionDomain,
            ProtosActorModuleState moduleState,
            int mailboxCapacity) {
        this(actorRefPrototype, executionDomain, moduleState, mailboxCapacity, null, false);
    }

    private ProtosActor(
            ProtosObjectValue actorRefPrototype,
            ProtosActorExecutionDomain executionDomain,
            ProtosActorModuleState moduleState,
            int mailboxCapacity,
            ProtosProcessRuntime processRuntime,
            boolean rootActor) {
        this.executionDomain =
                Objects.requireNonNull(executionDomain, "executionDomain");
        this.moduleState = Objects.requireNonNull(moduleState, "moduleState");
        if (rootActor && processRuntime == null) {
            throw new IllegalArgumentException("RootActor requires an owning Process runtime");
        }
        this.processRuntime = processRuntime;
        this.rootActor = rootActor;
        this.mailbox = new ProtosActorMailbox(mailboxCapacity);
        this.deliveryAdmission = new ProtosActorDeliveryAdmission(this);
        this.mailbox.bindAdmissionWakeup(deliveryAdmission::capacityAvailable);
        long nextIdentity = NEXT_INCARNATION_ID.incrementAndGet();
        if (nextIdentity <= 0) {
            throw new IllegalStateException("Actor incarnation identity space exhausted");
        }
        this.incarnationIdentity = nextIdentity;
        this.reference =
                new ProtosActorRefValue(
                        Objects.requireNonNull(actorRefPrototype, "actorRefPrototype"), this);
        this.executionDomain.bindActor(this);
    }

    public LifecycleState lifecycleState() {
        return lifecycle.get();
    }

    public ProtosActorExecutionDomain executionDomain() {
        return executionDomain;
    }

    public ProtosActorModuleState moduleState() {
        return moduleState;
    }

    public ProtosActorRefValue reference() {
        return reference;
    }

    /** Internal hosting Process, if this Actor is attached to one. */
    public java.util.Optional<ProtosProcessRuntime> processForRuntime() {
        return java.util.Optional.ofNullable(processRuntime);
    }

    /** True only for the unique RootActor created by the owning Process runtime. */
    public boolean isRootActorForRuntime() {
        return rootActor;
    }

    /** Creates one Actor in the same local Process when this Actor has Process hosting. */
    public ProtosActor createHostedActorForRuntime(ProtosObjectValue actorRefPrototype) {
        Objects.requireNonNull(actorRefPrototype, "actorRefPrototype");
        return processRuntime == null
                ? new ProtosActor(actorRefPrototype)
                : processRuntime.createHostedActorForRuntime(actorRefPrototype);
    }

    void registerRoutingGroupForRuntime(ProtosActorGroupRuntime group) {
        synchronized (this) {
            routingGroups.add(Objects.requireNonNull(group, "group"));
        }
    }

    void unregisterRoutingGroupForRuntime(ProtosActorGroupRuntime group) {
        synchronized (this) {
            routingGroups.remove(group);
        }
    }

    private void notifyRoutingGroupsForRuntime() {
        List<ProtosActorGroupRuntime> groups;
        synchronized (this) {
            groups = List.copyOf(routingGroups);
        }
        for (ProtosActorGroupRuntime group : groups) {
            group.memberLifecycleChangedForRuntime(this);
        }
    }

    /**
     * Reports an unhandled fatal Actor failure to the nearest runtime failure authority.
     *
     * <p>Standalone Actors retain the existing consequence (terminate this incarnation). For a
     * Process-bound Actor, the Process runtime distinguishes non-root failure from RootActor
     * failure without exposing or transferring the internal failure object.
     */
    public void failForRuntime(Object failure) {
        Objects.requireNonNull(failure, "failure");
        if (processRuntime == null) {
            requestTerminationForRuntime();
            return;
        }
        processRuntime.actorFatalFailureForRuntime(this, failure);
    }

    synchronized boolean tryAcceptMessageForRuntime(ProtosTask.Continuation turn) {
        LifecycleState state = lifecycle.get();
        if (state == LifecycleState.TERMINATING || state == LifecycleState.TERMINATED) {
            return false;
        }
        return mailbox.tryAccept(turn);
    }

    ProtosActorMailbox mailboxForRuntime() {
        return mailbox;
    }

    ProtosActorDeliveryAttempt beginDeliveryForRuntime(
            ProtosActorRefValue sender, ProtosTask.Continuation turn) {
        return deliveryAdmission.submit(sender, turn);
    }

    ProtosActorDeliveryAdmission deliveryAdmissionForRuntime() {
        return deliveryAdmission;
    }

    /**
     * Fixes the destination execution environment used by ordinary external message turns.
     * Bootstrap owns this binding; message delivery never imports the sender's module environment.
     */
    public synchronized void bindMessageEnvironmentForRuntime(
            ProtosPrelude prelude, ProtosModuleKey canonicalModuleKey) {
        Objects.requireNonNull(prelude, "prelude");
        Objects.requireNonNull(canonicalModuleKey, "canonicalModuleKey");
        if (messagePrelude != null
                && (messagePrelude != prelude || !messageModuleKey.equals(canonicalModuleKey))) {
            throw new IllegalStateException("Actor message environment is already fixed");
        }
        messagePrelude = prelude;
        messageModuleKey = canonicalModuleKey;
    }

    ProtosActivation newMessageActivationForRuntime() {
        ProtosPrelude prelude;
        ProtosModuleKey moduleKey;
        synchronized (this) {
            prelude = messagePrelude;
            moduleKey = messageModuleKey;
        }
        if (prelude == null || moduleKey == null) {
            throw new IllegalStateException("Actor has no completed bootstrap message environment");
        }
        return prelude.newModuleActivation(
                moduleState,
                moduleKey,
                prelude.newExecutionContext(),
                executionDomain);
    }

    /** Destination-local behavior installed by successful bootstrap. */
    public java.util.Optional<ProtosObjectValue> currentBehavior() {
        return java.util.Optional.ofNullable(currentBehavior.get());
    }

    /**
     * Installs the exact bootstrap result and attempts the unique READY cutover.
     *
     * <p>If termination wins the lifecycle race first, this returns false and never reopens
     * the Actor. The installed behavior reference is never replaced.
     */
    public boolean completeInitialization(ProtosObjectValue behavior) {
        Objects.requireNonNull(behavior, "behavior");
        // I091-F4: a rich numeric value is physically object-backed,
        // but it is not an ordinary Actor behavior object.
        if (ProtosNumericValueSupport.isCurrentNumber(behavior)) {
            throw new IllegalArgumentException(
                    "Actor behavior must be an ordinary object");
        }
        boolean transitioned;
        synchronized (this) {
            if (lifecycle.get() != LifecycleState.INITIALIZING) {
                return false;
            }
            ProtosObjectValue existing = currentBehavior.get();
            if (existing != null && existing != behavior) {
                throw new IllegalStateException("Actor initial behavior is already fixed");
            }
            if (existing == null && !currentBehavior.compareAndSet(null, behavior)) {
                existing = currentBehavior.get();
                if (existing != behavior) {
                    throw new IllegalStateException("Actor initial behavior is already fixed");
                }
            }
            transitioned =
                    lifecycle.compareAndSet(
                            LifecycleState.INITIALIZING, LifecycleState.READY);
        }
        if (transitioned) {
            mailbox.signalRuntime();
            notifyRoutingGroupsForRuntime();
        }
        return transitioned;
    }

    long incarnationIdentityForRuntime() {
        return incarnationIdentity;
    }

    /**
     * Establishes the unique INITIALIZING -> READY cutover.
     *
     * @return true only for the call that performs the transition
     */
    public boolean markReady() {
        boolean transitioned;
        synchronized (this) {
            transitioned =
                    lifecycle.compareAndSet(
                            LifecycleState.INITIALIZING, LifecycleState.READY);
        }
        if (transitioned) {
            mailbox.signalRuntime();
            notifyRoutingGroupsForRuntime();
        }
        return transitioned;
    }

    /**
     * Establishes the irreversible transition into termination.
     *
     * @return true only for the call that performs the cutover
     */
    public boolean beginTermination() {
        boolean transitioned = false;
        synchronized (this) {
            while (true) {
                LifecycleState current = lifecycle.get();
                switch (current) {
                    case INITIALIZING, READY -> {
                        if (lifecycle.compareAndSet(current, LifecycleState.TERMINATING)) {
                            transitioned = true;
                            break;
                        }
                    }
                    case TERMINATING, TERMINATED -> {
                        return false;
                    }
                }
                if (transitioned) break;
            }
        }

        // The cutover owns cancellation, but producer callbacks may acquire another Actor's
        // admission lock. Never hold this Actor monitor while requesting those cancellations.
        deliveryAdmission.lifecycleChanged();
        notifyRoutingGroupsForRuntime();
        executionDomain.actorTerminationBegun();
        mailbox.signalRuntime();
        synchronized (this) {
            terminationCutoverCleanupComplete = true;
        }
        return true;
    }

    /** D121 first-close admission and optional D112/PLAT030 cleanup reservation. */
    boolean authorizeLifecycleCloseForRuntime(
            ProtosActivation activation,
            ProtosIoLifecycle lifecycleOwner,
            boolean reserveGuestCleanup) {
        Objects.requireNonNull(activation, "activation");
        Objects.requireNonNull(lifecycleOwner, "lifecycleOwner");
        if (activation.executionDomain() != executionDomain) throw new IllegalArgumentException("lifecycle close belongs to another Actor execution domain");
        synchronized (this) {
            LifecycleState current = lifecycle.get();
            if (current == LifecycleState.TERMINATED) return false;
            if (current == LifecycleState.TERMINATING && !activation.terminationCleanupAuthorizedForRuntime()) return false;
            if (reserveGuestCleanup) executionDomain.registerActorIoLifecycleCleanupForRuntime(lifecycleOwner);
            return true;
        }
    }

    /** Establishes graceful/fatal termination and completes it once task cleanup permits. */
    public void requestTerminationForRuntime() {
        beginTermination();
        tryCompleteTerminationForRuntime();
    }

    void tryCompleteTerminationForRuntime() {
        if (lifecycle.get() == LifecycleState.TERMINATING
                && !executionDomain.hasLiveTasksForRuntime()
                && !executionDomain.hasTerminationCleanupForRuntime()) {
            markTerminated();
        }
    }

    boolean registerTerminationObservationForRuntime(
            ProtosActorTerminationObservation observation) {
        Objects.requireNonNull(observation, "observation");
        synchronized (this) {
            if (lifecycle.get() == LifecycleState.TERMINATED) {
                return false;
            }
            terminationObservers.add(observation);
            return true;
        }
    }

    void unregisterTerminationObservationForRuntime(
            ProtosActorTerminationObservation observation) {
        synchronized (this) {
            terminationObservers.remove(observation);
        }
    }

    /**
     * Completes an already-started termination.
     *
     * @return true only for the call that performs TERMINATING -> TERMINATED
     */
    public boolean markTerminated() {
        List<ProtosActorTerminationObservation> notify;
        synchronized (this) {
            LifecycleState current = lifecycle.get();
            if (current == LifecycleState.TERMINATED) {
                return false;
            }
            if (current != LifecycleState.TERMINATING) {
                throw new IllegalStateException(
                        "Actor termination must begin before completion");
            }
            if (!terminationCutoverCleanupComplete
                    || executionDomain.hasLiveTasksForRuntime()
                    || executionDomain.hasTerminationCleanupForRuntime()) {
                return false;
            }
            lifecycle.set(LifecycleState.TERMINATED);
            notify = new ArrayList<>(terminationObservers);
            terminationObservers.clear();
        }
        deliveryAdmission.lifecycleChanged();
        notifyRoutingGroupsForRuntime();
        mailbox.signalRuntime();
        for (ProtosActorTerminationObservation observation : notify) {
            observation.targetTerminated();
        }
        if (processRuntime != null) {
            processRuntime.actorTerminatedForRuntime(this);
        }
        return true;
    }
}
