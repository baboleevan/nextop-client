package io.nextop.fragment;

import android.app.Fragment;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import io.nextop.NextopAndroid;
import io.nextop.R;
import io.nextop.client.MessageControlState;
import io.nextop.rx.RxFragment;
import rx.Observer;
import rx.android.schedulers.AndroidSchedulers;

import java.util.Arrays;
import java.util.List;

public class DebugMessagesFragment extends RxFragment {

    public static DebugMessagesFragment newInstance() {
        return new DebugMessagesFragment();
    }



    private MessageAdapter messageAdapter;


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_debug_messages, container, false);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        messageAdapter = new MessageAdapter();

        View view = getView();

        ListView listView = (ListView) view.findViewById(R.id.list);
        listView.setAdapter(messageAdapter);

        bind(NextopAndroid.getActive(getActivity()).getMessageControlState().getObservable().subscribeOn(AndroidSchedulers.mainThread())).subscribe(messageAdapter);
    }


    final class MessageAdapter extends BaseAdapter implements Observer<MessageControlState> {
        @Nullable
        MessageControlState mcs = null;


        List<MessageControlState.GroupSnapshot> groups;
        int[] groupNetSizes;



        @Override
        public void onNext(MessageControlState mcs) {
            this.mcs = mcs;

            groups = mcs.getGroups();
            int n = groups.size();
            groupNetSizes = new int[n + 1];
            for (int i = 0; i < n; ++i) {
                groupNetSizes[i + 1] = groupNetSizes[i - 1] + 1 + groups.get(i).size;
            }

            notifyDataSetChanged();
        }

        @Override
        public void onCompleted() {
            mcs = null;
            notifyDataSetChanged();
        }

        @Override
        public void onError(Throwable e) {
            mcs = null;
            notifyDataSetChanged();
        }


        @Override
        public int getCount() {
            return null != mcs ? groupNetSizes[groupNetSizes.length - 1] : 0;
        }

        @Override
        public Object getItem(int position) {
            int si = Arrays.binarySearch(groupNetSizes, position);
            if (0 <= si) {
                // a group
                return groups.get(si);
            } else if (0 < ~si) {
                // an entry
                return mcs.get(groups.get(~si).groupId, position - groupNetSizes[~si]);
            } else {
                throw new IllegalArgumentException();
            }
        }

        @Override
        public long getItemId(int position) {
            int si = Arrays.binarySearch(groupNetSizes, position);
            if (0 <= si) {
                // a group
                return groups.get(si).groupId.longHashCode();
            } else if (0 < ~si) {
                // an entry
                return mcs.get(groups.get(~si).groupId, position - groupNetSizes[~si]).id.longHashCode();
            } else {
                throw new IllegalArgumentException();
            }
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            int si = Arrays.binarySearch(groupNetSizes, position);
            if (0 <= si) {
                // a group
                return 0;
            } else if (0 < ~si) {
                // an entry
                return 1;
            } else {
                throw new IllegalArgumentException();
            }
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            // FIXME
            if (null == convertView) {
                convertView = LayoutInflater.from(parent.getContext()).inflate(R.layout.view_debug_message_entry, parent, false);
            }

            TextView routeView = (TextView) convertView.findViewById(R.id.route);
            Object item = getItem(position);
            if (item instanceof MessageControlState.Entry) {
                routeView.setText(((MessageControlState.Entry) item).message.toString());
            } else if (item instanceof MessageControlState.GroupSnapshot) {
                routeView.setText(((MessageControlState.GroupSnapshot) item).groupId.toString());
            } else {
                throw new IllegalArgumentException();
            }

            return convertView;
        }
    }

}
