/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.res.R;
import com.android.systemui.retail.domain.interactor.RetailModeInteractor;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/**
 * Controller for {@link QSFooterView}.
 */
@QSScope
public class QSFooterViewController extends ViewController<QSFooterView> implements QSFooter {

    private final UserTracker mUserTracker;
    private final QSPanelController mQsPanelController;
    private final TextView mBuildText;
    private final PageIndicator mPageIndicator;
    private final View mEditButton;
    private final FalsingManager mFalsingManager;
    private final ActivityStarter mActivityStarter;
    private final RetailModeInteractor mRetailModeInteractor;
    private final SystemUIDialog.Factory mSystemUIDialogFactory;

    @Inject
    QSFooterViewController(QSFooterView view,
            UserTracker userTracker,
            FalsingManager falsingManager,
            ActivityStarter activityStarter,
            QSPanelController qsPanelController,
            RetailModeInteractor retailModeInteractor,
            SystemUIDialog.Factory systemUIDialogFactory
    ) {
        super(view);
        mUserTracker = userTracker;
        mQsPanelController = qsPanelController;
        mFalsingManager = falsingManager;
        mActivityStarter = activityStarter;
        mRetailModeInteractor = retailModeInteractor;
        mSystemUIDialogFactory = systemUIDialogFactory;

        mBuildText = mView.findViewById(R.id.build);
        mPageIndicator = mView.findViewById(R.id.footer_page_indicator);
        mEditButton = mView.findViewById(android.R.id.edit);
    }

    @Override
    protected void onViewAttached() {
        mBuildText.setOnLongClickListener(view -> {
            mActivityStarter.executeRunnableDismissingKeyguard(() -> showFooterEditDialog(),
                    null, /* cancelAction */
                    true, /* dismissShade */
                    true, /* afterKeyguardGone */
                    false /* deferred */);
            return true;
        });

        mBuildText.setOnClickListener(view -> {
            Toast.makeText(getContext(), R.string.qs_footer_dialog_toast, Toast.LENGTH_SHORT)
                    .show();
        });

        mEditButton.setOnClickListener(view -> {
            if (mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                return;
            }
            mActivityStarter
                    .postQSRunnableDismissingKeyguard(() -> mQsPanelController.showEdit(view));
        });
        mQsPanelController.setFooterPageIndicator(mPageIndicator);
        mView.updateEverything();
    }

    @Override
    protected void onViewDetached() {}

    @Override
    public void setVisibility(int visibility) {
        mView.setVisibility(visibility);
        mEditButton
                .setVisibility(mRetailModeInteractor.isInRetailMode() ? View.GONE : View.VISIBLE);
        mEditButton.setClickable(visibility == View.VISIBLE);
    }

    @Override
    public void setExpanded(boolean expanded) {
        mView.setExpanded(expanded);
    }

    @Override
    public void setExpansion(float expansion) {
        mView.setExpansion(expansion);
    }

    @Override
    public void setKeyguardShowing(boolean keyguardShowing) {
        mView.setKeyguardShowing();
    }

    @Override
    public void disable(int state1, int state2, boolean animate) {
        mView.disable(state2);
    }

    private void showFooterEditDialog() {
        final InputMethodManager imm = (InputMethodManager) getContext().getSystemService(
                Context.INPUT_METHOD_SERVICE);
        final SystemUIDialog dialog = mSystemUIDialogFactory.create();
        final EditText editText = new EditText(getContext());

        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT);
        editText.setLayoutParams(lp);
        editText.setHint("YAAP");
        editText.setText(mBuildText.getText(), TextView.BufferType.EDITABLE);
        editText.setSelectAllOnFocus(true);
        editText.setSingleLine(true);
        editText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        editText.setRawInputType(InputType.TYPE_CLASS_TEXT);
        editText.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                setFooterText(editText.getText().toString());
                dialog.dismiss();
            }
            return true;
        });

        dialog.setTitle(R.string.qs_footer_dialog_title);
        dialog.setPositiveButton(com.android.internal.R.string.ok,
                (d, w) -> setFooterText(editText.getText().toString()));
        dialog.setOnShowListener(d -> {
            editText.requestFocus();
        });
        SystemUIDialog.registerDismissListener(dialog, () -> {
            if (imm != null) {
                imm.hideSoftInputFromWindow(editText.getWindowToken(), 0);
            }
        });
        dialog.setNegativeButton(R.string.cancel, null);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setView(editText);
        dialog.getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        dialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.show();
    }

    private void setFooterText(String text) {
        Settings.System.putString(getContext().getContentResolver(),
                Settings.System.QS_FOOTER_TEXT_STRING, text);
    }
}
