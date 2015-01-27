package io.nextop.demo.flip;

import android.net.Uri;
import io.nextop.Id;
import io.nextop.Message;
import io.nextop.Nextop;
import io.nextop.WireValue;
import io.nextop.rx.RxManager;
import io.nextop.vm.ImageViewModel;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func2;

import java.util.Map;
import java.util.concurrent.TimeUnit;

public class FlipViewModelManager extends ThinViewModelManager<FlipViewModel> {

    FlipViewModelManager(Nextop nextop) {
        super(nextop);
    }


    // CONTROLLER

    void addFrame(Id flipId, final FrameViewModel frameVm) {
        if (null != frameVm.imageVm.bitmap) {
            // vms should be treated as immutable, but here the caller gave up control
            // upload the image

            Message update = Message.newBuilder()
                    .setRoute("PUT http://" + Flip.REMOTE + "/flip/$flip-id/frame/$frame-id")
                    .set("flip-id", flipId)
                    .set("frame-id", frameVm.id)
                    .build();
            Id localId = nextop.send(Nextop.Layer.bitmap(frameVm.imageVm.bitmap), null).getId();
            frameVm.imageVm = ImageViewModel.local(localId);
        }

        update(flipId, new Func2<FlipViewModel, RxState, FlipViewModel>() {
            @Override
            public FlipViewModel call(FlipViewModel flipVm, RxState rxState) {
                flipVm.add(new FlipViewModel.FrameState(frameVm, -1L));
                return flipVm;
            }
        });
    }


    private void add(Id flipId, final Message results) {
        update(flipId, new Func2<FlipViewModel, RxState, FlipViewModel>() {
            @Override
            public FlipViewModel call(FlipViewModel flipVm, RxState rxState) {
                for (WireValue value : results.getContent().asList()) {
                    Map<WireValue, WireValue> m = value.asMap();

                    // FIXME asId
                    Id frameId = Id.valueOf(m.get("frame_id").asString());
                    long creationTime = m.get("creation_time").asLong();
                    String imageUrl = m.get("image_url").asString();
                    long updateIndex = m.get("most_recent_update_index").asLong();

                    FrameViewModel frameVm = new FrameViewModel(frameId);
                    frameVm.creationTime = creationTime;
                    frameVm.imageVm = ImageViewModel.remote(Uri.parse(imageUrl));


                    flipVm.add(new FlipViewModel.FrameState(frameVm, updateIndex));
                }
                return flipVm;
            }
        });

    }

    
    // VMM

    @Override
    protected void startUpdates(final FlipViewModel flipVm, final RxState state) {
        // sync
        // get updates. updates are (frame id, creation time)
        // in flipvm sort by creation time to get the final ordering

        Message sync = Message.newBuilder()
                .setRoute("GET http://" + Flip.REMOTE + "/flip/$flip-id/frame")
                .build();
        state.add(nextop.send(sync)
                .doOnNext(new Action1<Message>() {
                    @Override
                    public void call(Message message) {
                        add(flipVm.id, message);
                        complete(flipVm.id);
                    }
                }).doOnCompleted(new Action0() {
                    @Override
                    public void call() {
                        // start the poller
                        Action0 poller = new Action0() {
                            @Override
                            public void call() {
                                Message poll = Message.newBuilder()
                                        .setRoute("GET http://" + Flip.REMOTE + "/flip/$flip-id/frame")
                                        .set("after", flipVm.getMaxUpdateIndex())
                                        .build();
                                state.add(nextop.send(poll)
                                        .doOnNext(new Action1<Message>() {
                                            @Override
                                            public void call(final Message message) {
                                            add(flipVm.id, message);
                                            }
                                        })).subscribe();
                            }
                        };

                        state.add(AndroidSchedulers.mainThread().createWorker().schedulePeriodically(poller,
                                pollTimeoutMs, pollTimeoutMs, TimeUnit.MILLISECONDS));

                    }
                })).subscribe();
    }

    @Override
    protected FlipViewModel create(Id id) {
        return new FlipViewModel(id);
    }
}
