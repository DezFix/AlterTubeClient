package org.schabi.newpipe.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RelativeLayout;

import androidx.annotation.Nullable;

import org.schabi.newpipe.BaseFragment;
import org.schabi.newpipe.R;
import org.schabi.newpipe.views.ShortsPlayerActivity;

public class BlankFragment extends BaseFragment {
    private static final String ARG_SHORTS_PLACEHOLDER = "shorts_placeholder";

    private boolean shortsPlaceholder;

    public static BlankFragment newShortsPlaceholder() {
        final BlankFragment fragment = new BlankFragment();
        final Bundle arguments = new Bundle();
        arguments.putBoolean(ARG_SHORTS_PLACEHOLDER, true);
        fragment.setArguments(arguments);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        shortsPlaceholder = getArguments() != null
                && getArguments().getBoolean(ARG_SHORTS_PLACEHOLDER, false);
    }

    @Nullable
    @Override
    public View onCreateView(final LayoutInflater inflater, @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        final View root = inflater.inflate(R.layout.fragment_blank, container, false);
        if (shortsPlaceholder) {
            final Button openButton = new Button(requireContext());
            openButton.setText(R.string.shorts_open_player);
            final RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.addRule(RelativeLayout.CENTER_IN_PARENT);
            openButton.setOnClickListener(v -> startActivity(
                    new Intent(requireContext(), ShortsPlayerActivity.class)));
            ((ViewGroup) root).addView(openButton, params);
        }
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        setTitle(shortsPlaceholder ? getString(R.string.shorts_tab_title) : "AlterTube");
    }
}
