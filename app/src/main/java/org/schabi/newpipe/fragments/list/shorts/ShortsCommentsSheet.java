package org.schabi.newpipe.fragments.list.shorts;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.comments.CommentsInfo;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.util.ExtractorHelper;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Bottom sheet with comments for the current short.
 */
public class ShortsCommentsSheet extends BottomSheetDialogFragment {

    private static final String ARG_URL = "url";
    private static final String ARG_TITLE = "title";

    private final CompositeDisposable disposables = new CompositeDisposable();
    private ShortsCommentsAdapter adapter;
    private ProgressBar loading;
    private TextView titleView;

    public static ShortsCommentsSheet newInstance(final String url, final String title) {
        final ShortsCommentsSheet sheet = new ShortsCommentsSheet();
        final Bundle args = new Bundle();
        args.putString(ARG_URL, url);
        args.putString(ARG_TITLE, title);
        sheet.setArguments(args);
        return sheet;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.sheet_shorts_comments, container, false);
        final RecyclerView list = view.findViewById(R.id.shorts_comments_list);
        loading = view.findViewById(R.id.shorts_comments_loading);
        titleView = view.findViewById(R.id.shorts_comments_title);
        adapter = new ShortsCommentsAdapter();
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);
        load();
        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        disposables.clear();
    }

    private void load() {
        final Bundle args = getArguments();
        final String url = args == null ? null : args.getString(ARG_URL);
        final String title = args == null ? "" : args.getString(ARG_TITLE, "");
        if (url == null || url.isEmpty()) {
            dismissAllowingStateLoss();
            return;
        }
        loading.setVisibility(View.VISIBLE);
        disposables.add(ExtractorHelper
                .getCommentsInfo(ServiceList.YouTube.getServiceId(), url, false)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::showComments, throwable -> {
                    loading.setVisibility(View.GONE);
                    titleView.setText(R.string.shorts_comments_empty);
                }));
    }

    private void showComments(final CommentsInfo info) {
        loading.setVisibility(View.GONE);
        final List<CommentsInfoItem> items = new ArrayList<>();
        for (final InfoItem item : info.getRelatedItems()) {
            if (item instanceof CommentsInfoItem) {
                items.add((CommentsInfoItem) item);
            }
        }
        if (items.isEmpty()) {
            titleView.setText(R.string.shorts_comments_empty);
        } else {
            titleView.setText(getString(R.string.shorts_comments_title, items.size()));
        }
        adapter.setItems(items);
    }
}
