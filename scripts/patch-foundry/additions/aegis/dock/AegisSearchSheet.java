// Copyright 2026 The Aegis Browser Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.aegis.dock;

import static org.chromium.build.NullUtil.assumeNonNull;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.profiles.ProfileProvider;
import org.chromium.chrome.browser.search_engines.TemplateUrlServiceFactory;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabLaunchType;
import org.chromium.components.search_engines.TemplateUrl;
import org.chromium.components.search_engines.TemplateUrlService;
import org.chromium.content_public.browser.LoadUrlParams;

/**
 * Aegis glass search sheet (patch 081): the dock's URL-pill tap target. A
 * translucent full-screen overlay with a bottom sheet that carries a single
 * query field and the current default search engine as a chip. Typing a
 * domain navigates the current tab (a URL load); typing words searches with
 * the default engine (kept functional by design in Aegis). Suggestions and
 * per-engine chips land in a later sheet iteration.
 */
@NullMarked
public class AegisSearchSheet extends FrameLayout {
    private AegisSearchSheet(Context context) {
        super(context);
    }

    /** Builds and shows the sheet over the dock's activity content root. */
    public static void show(AegisDockView dock) {
        Activity activity = (Activity) dock.getContext();
        if (!(activity instanceof ChromeTabbedActivity)) return;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;

        Context context = dock.getContext();
        AegisSearchSheet sheet = new AegisSearchSheet(context);
        sheet.setId(R.id.aegis_search_sheet);
        sheet.setBackgroundColor(context.getColor(R.color.aegis_glass_scrim));
        sheet.setClickable(true);
        sheet.setOnClickListener(v -> dismiss(sheet));

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(context.getColor(R.color.aegis_glass_sheet));
        float corner = context.getResources().getDimension(R.dimen.aegis_sheet_corner);
        bg.setCornerRadii(
                new float[] {corner, corner, corner, corner, 0f, 0f, 0f, 0f});
        panel.setBackground(bg);
        int pad = context.getResources().getDimensionPixelSize(R.dimen.aegis_sheet_padding);
        panel.setPadding(pad, pad, pad, pad);

        TemplateUrl engine = defaultEngine(activity);
        String engineName =
                engine != null ? engine.getShortName()
                               : context.getString(R.string.aegis_dock_search);
        EditText input = new EditText(context);
        input.setId(R.id.aegis_search_input);
        input.setHint(context.getString(R.string.aegis_sheet_search, engineName));
        input.setSingleLine(true);
        input.setTextSize(16);
        input.setTextColor(context.getColor(R.color.aegis_glass_text));
        input.setHintTextColor(context.getColor(R.color.aegis_glass_text_secondary));
        input.setBackground(null);
        input.setPadding(0, pad / 2, 0, pad / 2);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setOnEditorActionListener(
                (v, actionId, event) -> {
                    if (actionId != EditorInfo.IME_ACTION_GO
                            && (event == null
                                    || event.getAction() != KeyEvent.ACTION_DOWN
                                    || event.getKeyCode() != KeyEvent.KEYCODE_ENTER)) {
                        return false;
                    }
                    go(sheet, panel, input);
                    return true;
                });
        panel.addView(input);

        TextView chip = new TextView(context);
        chip.setText(engineName);
        chip.setTextSize(12);
        chip.setTextColor(context.getColor(R.color.aegis_glass_chip_text));
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor(context.getColor(R.color.aegis_glass_chip));
        float chipCorner = pad / 2f;
        chipBg.setCornerRadius(chipCorner);
        chip.setPadding(pad / 2, pad / 4, pad / 2, pad / 4);
        chip.setBackground(chipBg);
        LinearLayout.LayoutParams chipLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.topMargin = pad / 2;
        panel.addView(chip, chipLp);

        content.addView(
                sheet,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        sheet.addView(
                panel,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM));

        input.requestFocus();
        input.post(
                () -> {
                    InputMethodManager imm =
                            (InputMethodManager)
                                    context.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(input, 0);
                });
    }

    private static void dismiss(AegisSearchSheet sheet) {
        InputMethodManager imm =
                (InputMethodManager)
                        sheet.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(sheet.getWindowToken(), 0);
        ViewGroup parent = (ViewGroup) sheet.getParent();
        if (parent != null) parent.removeView(sheet);
    }

    private static void go(AegisSearchSheet sheet, LinearLayout panel, EditText input) {
        String query = input.getText() == null ? "" : input.getText().toString().trim();
        if (TextUtils.isEmpty(query)) return;
        Activity activity = (Activity) sheet.getContext();
        if (!(activity instanceof ChromeTabbedActivity)) return;
        ChromeTabbedActivity cta = (ChromeTabbedActivity) activity;
        String url = resolve(query, defaultEngine(activity));
        if (url == null) return;
        dismiss(sheet);
        Tab tab = cta.getActivityTab();
        LoadUrlParams params = new LoadUrlParams(url);
        if (tab != null) {
            tab.loadUrl(params);
        } else {
            cta.getTabCreator(false)
                    .createNewTab(params, TabLaunchType.FROM_LINK, /* parent= */ null);
        }
    }

    private static @Nullable TemplateUrl defaultEngine(Activity activity) {
        if (!(activity instanceof ChromeTabbedActivity)) return null;
        ChromeTabbedActivity cta = (ChromeTabbedActivity) activity;
        ProfileProvider provider = cta.getProfileProviderSupplier().get();
        if (provider == null) return null;
        Profile profile = provider.getOriginalProfile();
        TemplateUrlService service = TemplateUrlServiceFactory.getForProfile(profile);
        return service.getDefaultSearchEngineTemplateUrl();
    }

    /**
     * Resolves the typed text to a URL: explicit schemes and about: pass
     * through; a no-space dotted host becomes https://; anything else is a
     * search with the default engine's template URL ({searchTerms}).
     */
    private static @Nullable String resolve(String query, @Nullable TemplateUrl engine) {
        String trimmed = query.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")
                || trimmed.startsWith("about:") || trimmed.startsWith("chrome://")) {
            return trimmed;
        }
        boolean looksLikeUrl = !trimmed.contains(" ") && trimmed.indexOf('.') > 0;
        if (looksLikeUrl) return "https://" + trimmed;
        if (engine == null) return null;
        String template = engine.getURL();
        if (TextUtils.isEmpty(template)) return null;
        return template
                .replace("{searchTerms}", android.net.Uri.encode(trimmed))
                .replace("{inputEncoding}", "UTF-8")
                .replace("{outputEncoding}", "UTF-8");
    }
}
