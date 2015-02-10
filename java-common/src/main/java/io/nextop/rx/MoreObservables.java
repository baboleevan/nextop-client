package io.nextop.rx;

import rx.Observable;
import rx.Subscriber;

public final class MoreObservables {

    public static <T> Observable<T> hanging() {
        return Observable.create(new Observable.OnSubscribe<T>() {
            @Override
            public void call(Subscriber<? super T> subscriber) {
                /* hang ... */
            }
        });
    }


    private MoreObservables() {
    }
}
