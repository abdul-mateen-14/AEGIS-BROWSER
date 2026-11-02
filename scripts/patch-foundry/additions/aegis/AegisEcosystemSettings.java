// Copyright 2026 The Aegis Browser Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.aegis;

import static org.chromium.build.NullUtil.assumeNonNull;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.preference.Preference;

import org.chromium.base.supplier.MonotonicObservableSupplier;
import org.chromium.base.supplier.ObservableSuppliers;
import org.chromium.base.supplier.SettableMonotonicObservableSupplier;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.settings.ChromeBaseSettingsFragment;
import org.chromium.chrome.browser.settings.search.ChromeBaseSearchIndexProvider;
import org.chromium.components.browser_ui.settings.EmbeddableSettingsPage;
import org.chromium.components.browser_ui.settings.SettingsFragment;
import org.chromium.components.browser_ui.settings.SettingsUtils;

/**
 * Aegis Ecosystem: the AEGIS product family behind this browser.
 *
 * Opens the project sites: the AM Products page (full product line),
 * CoreX, AEGIS OS and AEGIS SOC. Modeled on AboutChromeSettings, the
 * smallest working ChromeBaseSettingsFragment at the pinned tag.
 */
@NullMarked
public class AegisEcosystemSettings extends ChromeBaseSettingsFragment
        implements EmbeddableSettingsPage, Preference.OnPreferenceClickListener {
    private static final String PREF_PRODUCTS = "aegis_products";
    private static final String PREF_COREX = "aegis_corex";
    private static final String PREF_OS = "aegis_os";
    private static final String PREF_SOC = "aegis_soc";

    private final SettableMonotonicObservableSupplier<String> mPageTitle =
            ObservableSuppliers.createMonotonic();

    @Override
    public void onCreatePreferences(@Nullable Bundle bundle, @Nullable String s) {
        mPageTitle.set("Aegis Ecosystem");
        SettingsUtils.addPreferencesFromResource(this, R.xml.aegis_ecosystem_preferences);
        for (String key : new String[] {PREF_PRODUCTS, PREF_COREX, PREF_OS, PREF_SOC}) {
            Preference preference = findPreference(key);
            assumeNonNull(preference);
            preference.setOnPreferenceClickListener(this);
        }
    }

    @Override
    public MonotonicObservableSupplier<String> getPageTitle() {
        return mPageTitle;
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();
        String url = null;
        if (PREF_PRODUCTS.equals(key)) {
            url = "https://am.infy.uk/products/";
        } else if (PREF_COREX.equals(key)) {
            url = "https://corex.site.je/";
        } else if (PREF_OS.equals(key)) {
            url = "https://os-cloudflare.pages.dev/";
        } else if (PREF_SOC.equals(key)) {
            url = "https://agis-soc.whf.bz/";
        }
        if (url != null) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException e) {
                // Nothing on the device can open a URL; do nothing.
            }
        }
        return true;
    }

    @Override
    public @SettingsFragment.AnimationType int getAnimationType() {
        return SettingsFragment.AnimationType.PROPERTY;
    }

    @Override
    public @Nullable String getMainMenuKey() {
        return "aegis_ecosystem";
    }

    public static final ChromeBaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new ChromeBaseSearchIndexProvider(
                    AegisEcosystemSettings.class.getName(),
                    R.xml.aegis_ecosystem_preferences);
}
