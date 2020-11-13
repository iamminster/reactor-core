/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.test.publisher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.stream.Stream;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Exceptions;
import reactor.core.Fuseable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.util.annotation.Nullable;

/**
 * A cold implementation of a {@link TestPublisher}.
 *
 * @author Simon Basle
 * @author Stephane Maldini
 */
final class ColdTestPublisher<T> extends TestPublisher<T> {

	@SuppressWarnings("rawtypes")
	private static final ColdTestPublisherSubscription[] EMPTY = new ColdTestPublisherSubscription[0];

	final List<T> values;
	Throwable     error;
	final Behavior behavior;

	volatile boolean wasRequested;

	@SuppressWarnings("unchecked")
	volatile ColdTestPublisherSubscription<T>[] subscribers = EMPTY;

	volatile int cancelCount;
	static final AtomicIntegerFieldUpdater<ColdTestPublisher> CANCEL_COUNT =
			AtomicIntegerFieldUpdater.newUpdater(ColdTestPublisher.class, "cancelCount");

	volatile long subscribeCount;
	static final AtomicLongFieldUpdater<ColdTestPublisher> SUBSCRIBED_COUNT =
			AtomicLongFieldUpdater.newUpdater(ColdTestPublisher.class, "subscribeCount");

	ColdTestPublisher(Behavior behavior) {
		this.behavior = behavior;
		this.values = Collections.synchronizedList(new ArrayList<>());
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {
		Objects.requireNonNull(s, "s");

		ColdTestPublisherSubscription<T> p = new ColdTestPublisherSubscription<>(s, this);
		add(p);
		if (p.cancelled) {
			remove(p);
		}
		ColdTestPublisher.SUBSCRIBED_COUNT.incrementAndGet(this);

		s.onSubscribe(p); // will trigger drain() via request()

	}

	void add(ColdTestPublisherSubscription<T> s) {
		synchronized (this) {

			ColdTestPublisherSubscription<T>[] a = subscribers;
			int len = a.length;

			@SuppressWarnings("unchecked")
			ColdTestPublisherSubscription<T>[] b = new ColdTestPublisherSubscription[len + 1];
			System.arraycopy(a, 0, b, 0, len);
			b[len] = s;

			subscribers = b;
		}
	}

	@SuppressWarnings("unchecked")
	void remove(ColdTestPublisherSubscription<T> s) {
		ColdTestPublisherSubscription<T>[] a = subscribers;

		if (a == EMPTY) {
			return;
		}

		synchronized (this) {
			a = subscribers;
			if (a == EMPTY) {
				return;
			}
			int len = a.length;

			int j = -1;

			for (int i = 0; i < len; i++) {
				if (a[i] == s) {
					j = i;
					break;
				}
			}
			if (j < 0) {
				return;
			}
			if (len == 1) {
				subscribers = EMPTY;
				return;
			}

			ColdTestPublisherSubscription<T>[] b = new ColdTestPublisherSubscription[len - 1];
			System.arraycopy(a, 0, b, 0, j);
			System.arraycopy(a, j + 1, b, j, len - j - 1);

			subscribers = b;
		}
	}

	static final class ColdTestPublisherSubscription<T> implements Subscription {

		final Subscriber<? super T>                     actual;
		final Fuseable.ConditionalSubscriber<? super T> actualConditional;

		final ColdTestPublisher<T> parent;

		volatile boolean cancelled;

		volatile long requested;

		@SuppressWarnings("rawtypes")
		static final AtomicLongFieldUpdater<ColdTestPublisherSubscription>
				REQUESTED =
				AtomicLongFieldUpdater.newUpdater(ColdTestPublisherSubscription.class, "requested");

		/** Where in the {@link ColdTestPublisher#values} buffer this subscription is at. */
		int index;


		@SuppressWarnings("unchecked")
		ColdTestPublisherSubscription(Subscriber<? super T> actual, ColdTestPublisher<T> parent) {
			this.actual = actual;
			if(actual instanceof Fuseable.ConditionalSubscriber){
				this.actualConditional = (Fuseable.ConditionalSubscriber<? super T>) actual;
			}
			else {
				this.actualConditional = null;
			}
			this.parent = parent;
		}

		@Override
		public void request(long n) {
			if (Operators.validate(n)) {
				if (Operators.addCap(REQUESTED, this, n) == 0) {
					parent.wasRequested = true;
					drain(n);
				}
			}
		}

		@Override
		public void cancel() {
			if (!cancelled) {
				ColdTestPublisher.CANCEL_COUNT.incrementAndGet(parent);
				cancelled = true;
				parent.remove(this);
			}
		}

		private boolean onNext(T value) {
			if (actualConditional != null) {
				return actualConditional.tryOnNext(value);
			} else {
				actual.onNext(value);
				return true;
			}
		}

		void onError(Throwable e) {
			parent.remove(this);
			actual.onError(e);
		}

		void onComplete() {
			parent.remove(this);
			actual.onComplete();
		}

		private void drain(long n) {
			long r;
			switch (parent.behavior){
			case BUFFER:
			case ERROR:
				r = Math.min(parent.values.size() - index, this.requested);
				break;
			case MISBEHAVE:
				r =	this.requested == Long.MAX_VALUE ? parent.values.size() - index : this.requested;
				break;
			default:
				throw new IllegalStateException("Disallowed Behavior");
			}

			int i = index;
			int emitted = 0;
			for ( ; ; ) {
				if (cancelled) {
					return;
				}

				while (i != parent.values.size() && emitted != n) {
					T t = parent.values.get(i);
					// TODO allow violation null
					if (t == null) {
						actual.onError(new NullPointerException("The " + i + "th element was null"));
						return;
					}

					if (this.onNext(t)) {
						emitted++;
					}
					i++;
					if (cancelled) {
						return;
					}
				}



				n = requested;
				if (n == emitted) {
					index = i;
					n = REQUESTED.addAndGet(this, -emitted);
					if (i == parent.values.size()) {
						if (parent.error == Exceptions.TERMINATED) {
							this.onComplete();
						}
						else if (parent.error != null) {
							this.onError(parent.error);
						}
						return;
					}
					if (n == 0) {
						return;
					}
					emitted = 0;
				} else if (n == Long.MAX_VALUE) {
					index = i;
					emitted = 0;
					if (i == parent.values.size()) {
						if (parent.error == Exceptions.TERMINATED) {
							this.onComplete();
						}
						else if (parent.error != null) {
							this.onError(parent.error);
						}
						return;
					}
				} else if (i == parent.values.size()) {
					index = i;
					if (parent.error == Exceptions.TERMINATED) {
						this.onComplete();
					}
					else if (parent.error != null) {
						this.onError(parent.error);
					}
					return;
				}

			}
		}
	}

	@Override
	public Flux<T> flux() {
		return Flux.from(this);
	}

	@Override
	public boolean wasSubscribed() {
		return subscribeCount > 0;
	}

	@Override
	public long subscribeCount() {
		return subscribeCount;
	}

	@Override
	public boolean wasCancelled() {
		return cancelCount > 0;
	}

	@Override
	public boolean wasRequested() {
		return wasRequested;
	}

	@Override
	public Mono<T> mono() {
		return Mono.from(this);
	}

	@Override
	public ColdTestPublisher<T> assertMinRequested(long n) {
		ColdTestPublisherSubscription<T>[] subs = subscribers;
		long minRequest = Stream.of(subs)
		                        .mapToLong(s -> s.requested)
		                        .min()
		                        .orElse(0);
		if (minRequest < n) {
			throw new AssertionError("Expected smallest requested amount to be >= " + n + "; got " + minRequest);
		}
		return this;
	}

	@Override
	public ColdTestPublisher<T> assertMaxRequested(long n) {
		ColdTestPublisherSubscription<T>[] subs = subscribers;
		long maxRequest = Stream.of(subs)
		                        .mapToLong(s -> s.requested)
		                        .max()
		                        .orElse(0);
		if (maxRequest > n) {
			throw new AssertionError("Expected largest requested amount to be <= " + n + "; got " + maxRequest);
		}
		return this;
	}

	@Override
	public ColdTestPublisher<T> assertSubscribers() {
		ColdTestPublisherSubscription<T>[] s = subscribers;
		if (s == EMPTY) {
			throw new AssertionError("Expected subscribers");
		}
		return this;
	}

	@Override
	public ColdTestPublisher<T> assertSubscribers(int n) {
		int sl = subscribers.length;
		if (sl != n) {
			throw new AssertionError("Expected " + n + " subscribers, got " + sl);
		}
		return this;
	}

	@Override
	public ColdTestPublisher<T> assertNoSubscribers() {
		int sl = subscribers.length;
		if (sl != 0) {
			throw new AssertionError("Expected no subscribers, got " + sl);
		}
		return this;
	}

	@Override
	public ColdTestPublisher<T> assertCancelled() {
		if (cancelCount == 0) {
			throw new AssertionError("Expected at least 1 cancellation");
		}
		return this;
	}

	@Override
	public ColdTestPublisher<T> assertCancelled(int n) {
		int cc = cancelCount;
		if (cc != n) {
			throw new AssertionError("Expected " + n + " cancellations, got " + cc);
		}
		return this;
	}

	@Override
	public ColdTestPublisher<T> assertNotCancelled() {
		if (cancelCount != 0) {
			throw new AssertionError("Expected no cancellation");
		}
		return this;
	}

	@Override
	public ColdTestPublisher<T> assertRequestOverflow() {
		//the cold publisher cannot really overflow requests, as it immediately throws
		throw new AssertionError("Expected some request overflow");
	}

	@Override
	public ColdTestPublisher<T> assertNoRequestOverflow() {
		//the cold publisher cannot really overflow requests, as it immediately throws
		return this;
	}

	@Override
	public ColdTestPublisher<T> next(@Nullable T t) {
		Objects.requireNonNull(t, "emitted values must be non-null");

		values.add(t);
		for (ColdTestPublisherSubscription<T> s : subscribers) {
			s.drain(s.requested);
		}

		return this;
	}

	@Override
	public ColdTestPublisher<T> error(Throwable t) {
		Objects.requireNonNull(t, "t");

		error = t;
		ColdTestPublisherSubscription<?>[] subs = subscribers;
		for (ColdTestPublisherSubscription<?> s : subs) {
			s.drain(s.requested);
		}
		return this;
	}

	@Override
	public ColdTestPublisher<T> complete() {
		ColdTestPublisherSubscription<?>[] subs = subscribers;
		error = Exceptions.TERMINATED;
		for (ColdTestPublisherSubscription<?> s : subs) {
			s.drain(s.requested);
		}
		return this;
	}

}