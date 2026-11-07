// Copyright 2026 The Aegis Browser Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.aegis.dock;

import static org.chromium.build.NullUtil.assumeNonNull;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.chromium.base.supplier.Supplier;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.ui.appmenu.AppMenuHandler;

/**
 * Aegis Liquid Glass dock: the bottom-edge navigation pill that replaces the
 * hidden native top toolbar (patch 080). The dock is a plain View overlay
 * attached to the activity's content root, above all browser chrome and web
 * content, so no compositor or rendering-pipeline changes are needed.
 *
 * Layout (spec Tables 5-11, aegis_tokens.xml):
 *   [ menu ] [ URL / trust pill ] [ search ] [ new tab ] [ tabs ]
 * The URL pill shows the current tab's page title with the URL as a subtitle
 * ( refreshed at 1 Hz - no observer wiring into the tab model yet). Tapping
 * the URL pill opens the glass search sheet (patch 081); the menu button
 * opens the regular app menu anchored to the dock (bottom bar variant).
 *
 * Collapsed state: after 4 s without input the dock shrinks to a 24x140dp
 * edge sliver on the trailing edge; touching it recalls the dock. Scroll-
 * driven hiding is not wired yet (the sliver timer stands in for it).
 */
@NullMarked
public class AegisDockView extends FrameLayout {
    /** Collapse delay after the last interaction, in ms. */
    private static final long COLLAPSE_DELAY_MS = 4000;
    /** URL refresh cadence, in ms. */
    private static final long REFRESH_MS = 1000;

    private final LinearLayout mPill;
    private final TextView mUrlPill;
    private final View mSliver;
    private final Supplier<@Nullable AppMenuHandler> mMenuSupplier;
    private final Runnable mCollapse = this::collapse;
    private final Runnable mRefresh = this::refreshUrl;
    private boolean mExpanded;
    private @Nullable String mLastBound;

    /** Installs the dock into {@code activity} exactly once per content root. */
    public static void install(
            ChromeTabbedActivity activity, Supplier<@Nullable AppMenuHandler> menuSupplier) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        if (content.findViewById(R.id.aegis_dock) != null) return;
        AegisDockView dock = new AegisDockView(activity, menuSupplier);
        dock.setId(R.id.aegis_dock);
        content.addView(
                dock,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
    }

    private AegisDockView(
            ChromeTabbedActivity activity, Supplier<@Nullable AppMenuHandler> menuSupplier) {
        super(activity);
        mMenuSupplier = menuSupplier;
        Resources res = getResources();

        // Full-width hit surface; the pill is centered inside it.
        mPill = new LinearLayout(getContext());
        mPill.setOrientation(LinearLayout.HORIZONTAL);
        mPill.setGravity(Gravity.CENTER_VERTICAL);
        mPill.setClickable(true);
        mPill.setFocusable(true);
        mPill.setBackgroundResource(R.drawable.aegis_dock_pill);
        int hpad = res.getDimensionPixelSize(R.dimen.aegis_dock_hpadding);
        mPill.setPadding(hpad, 0, hpad, 0);

        mPill.addView(makeButton(R.drawable.aegis_ic_menu, R.string.aegis_dock_menu,
                this::onMenuClicked));
        mUrlPill = new TextView(getContext());
        mUrlPill.setTextSize(13);
        mUrlPill.setTextColor(Color.WHITE);
        mUrlPill.setSingleLine(true);
        mUrlPill.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        mUrlPill.setContentDescription(getContext().getString(R.string.aegis_dock_search));
        LinearLayout.LayoutParams urlLp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int vpad = res.getDimensionPixelSize(R.dimen.aegis_dock_url_padding);
        mUrlPill.setPadding(vpad, 0, vpad, 0);
        mUrlPill.setOnClickListener(v -> openSearchOverlay());
        mPill.addView(mUrlPill, urlLp);
        mPill.addView(makeButton(R.drawable.aegis_ic_search, R.string.aegis_dock_search,
                this::openSearchOverlay));
        mPill.addView(makeButton(R.drawable.aegis_ic_plus, R.string.aegis_dock_new_tab,
                this::onNewTabClicked));
        mPill.addView(makeButton(R.drawable.aegis_ic_tabs, R.string.aegis_dock_tabs,
                this::onTabsClicked));

        FrameLayout.LayoutParams pillLp =
                new FrameLayout.LayoutParams(
                        res.getDimensionPixelSize(R.dimen.aegis_dock_width),
                        res.getDimensionPixelSize(R.dimen.aegis_dock_height),
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        int vmargin = res.getDimensionPixelSize(R.dimen.aegis_dock_bottom_margin);
        pillLp.bottomMargin = vmargin;
        addView(mPill, pillLp);

        // Collapsed edge sliver: a thin rounded handle on the trailing edge.
        mSliver = new View(getContext());
        mSliver.setBackgroundResource(R.drawable.aegis_dock_sliver);
        FrameLayout.LayoutParams sliverLp =
                new FrameLayout.LayoutParams(
                        res.getDimensionPixelSize(R.dimen.aegis_sliver_width),
                        res.getDimensionPixelSize(R.dimen.aegis_sliver_height),
                        Gravity.BOTTOM | Gravity.END);
        sliverLp.bottomMargin = vmargin + res.getDimensionPixelSize(R.dimen.aegis_dock_height) / 2
                - res.getDimensionPixelSize(R.dimen.aegis_sliver_height) / 2;
        mSliver.setVisibility(GONE);
        mSliver.setOnClickListener(v -> expand());
        mSliver.setContentDescription(getContext().getString(R.string.aegis_dock_recall));
        addView(mSliver, sliverLp);

        // The pill is a capsule; the sliver a small rounded bar.
        setOutlineFor(mPill, corner);
        setOutlineFor(mSliver, res.getDimensionPixelSize(R.dimen.aegis_sliver_width) / 2);

        expand();
    }

    private ImageView makeButton(int iconRes, int descRes, Runnable action) {
        ImageView btn = new ImageView(getContext());
        btn.setImageResource(iconRes);
        btn.setBackgroundResource(R.drawable.aegis_dock_button);
        btn.setContentDescription(getContext().getString(descRes));
        int size = getResources().getDimensionPixelSize(R.dimen.aegis_dock_button);
        int gap = getResources().getDimensionPixelSize(R.dimen.aegis_dock_gap);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.leftMargin = gap;
        btn.setOnClickListener(v -> action.run());
        return btn;
    }

    private void setOutlineFor(View view, float cornerRadius) {
        view.setOutlineProvider(
                new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View v, Outline outline) {
                        outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), cornerRadius);
                    }
                });
        view.setClipToOutline(true);
    }

    /** Collapses the dock to the edge sliver. */
    public void collapse() {
        if (!mExpanded) return;
        mExpanded = false;
        mPill.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            mPill.setVisibility(GONE);
            mSliver.setAlpha(0f);
            mSliver.setVisibility(VISIBLE);
            mSliver.animate().alpha(1f).setDuration(140).start();
        }).start();
    }

    /** Expands the full dock pill. */
    public void expand() {
        if (mExpanded) return;
        mExpanded = true;
        mSliver.animate().alpha(0f).setDuration(120).withEndAction(() -> mSliver.setVisibility(GONE))
                .start();
        mPill.setAlpha(0f);
        mPill.setVisibility(VISIBLE);
        mPill.animate().alpha(1f).setDuration(180).start();
        refreshUrl();
        scheduleCollapse();
    }

    private void scheduleCollapse() {
        removeCallbacks(mCollapse);
        // The dock never collapses while the app menu is open above it.
        AppMenuHandler handler = mMenuSupplier.get();
        if (handler != null && handler.isAppMenuShowing()) return;
        postDelayed(mCollapse, COLLAPSE_DELAY_MS);
    }

    private void refreshUrl() {
        Activity activity = (Activity) getContext();
        if (!(activity instanceof ChromeTabbedActivity)) return;
        ChromeTabbedActivity cta = (ChromeTabbedActivity) activity;
        Tab tab = cta.getActivityTab();
        String next;
        if (tab == null || !tab.isLoading()) {
            next = getContext().getString(R.string.aegis_dock_search_hint);
        } else {
            String title = tab.getTitle();
            next = (title == null || title.isEmpty())
                    ? stripScheme(tab.getUrl().getSpec())
                    : title;
        }
        if (next == null) next = getContext().getString(R.string.aegis_dock_search_hint);
        if (!next.equals(mLastBound)) {
            mLastBound = next;
            mUrlPill.setText(next);
        }
    }

    private static String stripScheme(@Nullable String spec) {
        if (spec == null) return "";
        if (spec.startsWith("https://")) return spec.substring(8);
        if (spec.startsWith("http://")) return spec.substring(7);
        return spec;
    }

    private void onMenuClicked() {
        AppMenuHandler handler = mMenuSupplier.get();
        if (handler != null) {
            handler.showAppMenu(mUrlPill, false, true);
        }
        scheduleCollapse();
    }

    private void onNewTabClicked() {
        Activity activity = (Activity) getContext();
        if (!(activity instanceof ChromeTabbedActivity)) return;
        ((ChromeTabbedActivity) activity)
                .onMenuOrKeyboardAction(
                        R.id.new_tab_menu_id, /* fromMenu= */ false,
                        /* menuItemData= */ null, /* triggeringMotion= */ null);
    }

    private void onTabsClicked() {
        Activity activity = (Activity) getContext();
        if (!(activity instanceof ChromeTabbedActivity)) return;
        ((ChromeTabbedActivity) activity)
                .onMenuOrKeyboardAction(
                        R.id.tab_search, /* fromMenu= */ false,
                        /* menuItemData= */ null, /* triggeringMotion= */ null);
    }

    /**
     * Opens the glass search sheet. Patch 080 ships this as a hook stub so
     * the dock compiles standalone; patch 081 (AegisSearchSheet) rewrites
     * the body to show the sheet.
     */
    public void openSearchOverlay() {
        // Patch 081 replaces this body with the glass search sheet.
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        postDelayed(mRefresh, REFRESH_MS);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(mRefresh);
        removeCallbacks(mCollapse);
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        @SuppressLint("UnusedVariable")
        boolean result = super.dispatchTouchEvent(event);
        scheduleCollapse();
        return result;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // Keep clear of the system gesture area on the bottom edge.
        Rect insets = new Rect();
        getWindowVisibleDisplayFrame(insets);
        int bottomInset = Math.max(0, getRootView().getHeight() - insets.bottom);
        if (mPill.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mPill.getLayoutParams();
            int wanted = getResources().getDimensionPixelSize(R.dimen.aegis_dock_bottom_margin)
                    + bottomInset;
            if (lp.bottomMargin != wanted) {
                lp.bottomMargin = wanted;
                mPill.setLayoutParams(lp);
            }
        }
    }
}
