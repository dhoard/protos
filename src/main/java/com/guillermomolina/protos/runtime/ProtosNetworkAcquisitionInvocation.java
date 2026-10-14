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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Private listen/accept targeted completion and untransferred custody handoff.
 * A backend thread never performs guest materialization or resolves a guest Future.
 */
final class ProtosNetworkAcquisitionInvocation {
    private final ProtosActorExecutionDomain domain;
    private final ProtosIoOperation operation;
    private final List<AtomicReference<Runnable>> pending = new ArrayList<>();
    private volatile Thread synchronousCaller;
    private Runnable cancellation;
    private boolean cancellationRequested;

    ProtosNetworkAcquisitionInvocation(
            ProtosActorExecutionDomain domain, ProtosIoOperation operation) {
        this.domain = Objects.requireNonNull(domain, "domain");
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    void enterBackendCall() {
        synchronousCaller = Thread.currentThread();
    }

    void exitBackendCall() {
        synchronousCaller = null;
    }

    void deliver(BooleanSupplier action, Runnable releaseOnDrop) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(releaseOnDrop, "releaseOnDrop");

        if (synchronousCaller == Thread.currentThread()) {
            action.getAsBoolean();
            return;
        }

        AtomicReference<Runnable> delivery = new AtomicReference<>(releaseOnDrop);
        synchronized (this) {
            pending.add(delivery);
        }

        try {
            domain.enqueueTargetedFutureCompletionForRuntime(
                    operation.future(),
                    () -> claim(delivery) && action.getAsBoolean());
        } catch (IllegalStateException terminatingActor) {
            if (claim(delivery)) releaseSafely(releaseOnDrop);
        }
    }

    private boolean claim(AtomicReference<Runnable> delivery) {
        boolean claimed = delivery.getAndSet(null) != null;
        synchronized (this) {
            pending.remove(delivery);
        }
        return claimed;
    }

    void requestCancellation() {
        boolean terminating = domain.actorTerminationBegunForRuntime();
        Runnable toCancel;
        List<AtomicReference<Runnable>> dropped = List.of();

        synchronized (this) {
            cancellationRequested = true;
            toCancel = cancellation;
            if (terminating) {
                dropped = List.copyOf(pending);
                pending.clear();
            }
        }

        if (toCancel != null) toCancel.run();
        for (AtomicReference<Runnable> delivery : dropped) {
            Runnable release = delivery.getAndSet(null);
            if (release != null) releaseSafely(release);
        }
    }

    void install(Runnable installed) {
        boolean cancelNow;
        synchronized (this) {
            cancellation = installed;
            cancelNow = cancellationRequested && installed != null;
        }
        if (cancelNow) installed.run();
    }

    private static void releaseSafely(Runnable release) {
        try {
            release.run();
        } catch (RuntimeException ignored) {
            // A dropped host resource cannot produce a second portable outcome.
        }
    }
}
