package com.fongmi.android.tv.ui.fragment;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.FragmentBlankBinding;
import com.fongmi.android.tv.ui.base.BaseFragment;

public class BlankFragment extends BaseFragment {

    public static BlankFragment newInstance() {
        return new BlankFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return FragmentBlankBinding.inflate(inflater, container, false);
    }
}
