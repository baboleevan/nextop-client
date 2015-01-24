package io.nextop.demo.flip;

import io.nextop.Id;
import io.nextop.rx.RxManaged;

import java.util.ArrayList;
import java.util.List;

public class FeedViewModel extends RxManaged {
    // ids
    // model internally orders by last modified time

    // last update id

    // on change observer


    List<Id> flipIds = new ArrayList<Id>(50);


    public FeedViewModel(Id feedId) {
        super(feedId);
    }


    public int size() {
        return 100 * flipIds.size();
    }


    public Id getFlipId(int index) {
        return flipIds.get(index % flipIds.size());
    }



}
