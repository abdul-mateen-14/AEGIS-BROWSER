// Copyright 2026 The Aegis Browser Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.aegis;

import static org.chromium.build.NullUtil.assumeNonNull;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
import org.json.JSONArray;
import org.json.JSONException;

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
    private static final String PREF_ID_STATUS = "aegis_id_status";
    private static final String PREF_ID_SIGNIN = "aegis_id_signin";
    private static final String PREF_ID_DEVICES = "aegis_id_devices";
    private static final String PREF_ID_SIGNOUT = "aegis_id_signout";

    private final SettableMonotonicObservableSupplier<String> mPageTitle =
            ObservableSuppliers.createMonotonic();

    private AegisIdManager mIdManager;

    @Override
    public void onCreatePreferences(@Nullable Bundle bundle, @Nullable String s) {
        mPageTitle.set("Aegis Ecosystem");
        SettingsUtils.addPreferencesFromResource(this, R.xml.aegis_ecosystem_preferences);
        mIdManager = AegisIdManager.getInstance(requireContext());
        for (String key : new String[] {PREF_PRODUCTS, PREF_COREX, PREF_OS, PREF_SOC,
                PREF_ID_SIGNIN, PREF_ID_DEVICES, PREF_ID_SIGNOUT}) {
            Preference preference = findPreference(key);
            assumeNonNull(preference);
            preference.setOnPreferenceClickListener(this);
        }
        refreshIdentityState();
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
        if (PREF_ID_SIGNIN.equals(key)) {
            showSignInDialog();
        } else if (PREF_ID_DEVICES.equals(key)) {
            showDevicesDialog();
        } else if (PREF_ID_SIGNOUT.equals(key)) {
            confirmSignOut();
        }
        return true;
    }

    private void refreshIdentityState() {
        Preference status = findPreference(PREF_ID_STATUS);
        Preference devices = findPreference(PREF_ID_DEVICES);
        Preference signOut = findPreference(PREF_ID_SIGNOUT);
        boolean signedIn = mIdManager.isSignedIn();
        String email = mIdManager.signedInEmail();
        if (status != null) {
            status.setSummary(signedIn && !email.isEmpty() ? email : "Not signed in");
        }
        if (devices != null) {
            devices.setEnabled(signedIn);
        }
        if (signOut != null) {
            signOut.setVisible(signedIn);
        }
    }

    /**
     * Aegis ID sign-in / create-account dialog, built programmatically: two
     * EditTexts and two action buttons ("Sign in" and "Create account").
     * The buttons are disabled while the manager derives keys and talks to
     * the backend, then re-enabled when the callback arrives.
     */
    private void showSignInDialog() {
        Context context = requireContext();
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dialogPadding(context);
        layout.setPadding(pad, pad / 2, pad, 0);

        EditText emailField = new EditText(context);
        emailField.setHint("Email");
        emailField.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        emailField.setSingleLine(true);
        layout.addView(emailField);

        EditText passphraseField = new EditText(context);
        passphraseField.setHint("Passphrase - derives your sync keys and cannot be reset");
        passphraseField.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(passphraseField);

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Aegis ID")
                .setMessage("Sign in to sync this browser across your devices, or create a "
                        + "new Aegis ID. Your passphrase never leaves the device.")
                .setView(layout)
                .create();
        // Two action buttons on one dialog (via setButton): the positive
        // button signs in, the neutral button creates the account. Their
        // View-level click listeners are bound in onShow so a click does NOT
        // auto-dismiss the dialog - it stays open with the buttons disabled
        // ("Working...") while the manager runs, then the callback decides:
        // success dismisses, failure re-enables and shows a Toast.
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "Sign in",
                (DialogInterface.OnClickListener) null);
        dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Create account",
                (DialogInterface.OnClickListener) null);
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                bindSignInButtons(dialog, emailField, passphraseField);
            }
        });
        dialog.show();
    }

    private void bindSignInButtons(final AlertDialog dialog, final EditText emailField,
            final EditText passphraseField) {
        Button signIn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button create = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        assumeNonNull(signIn);
        assumeNonNull(create);
        signIn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                attemptAegisId(dialog, emailField, passphraseField, false);
            }
        });
        create.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                attemptAegisId(dialog, emailField, passphraseField, true);
            }
        });
    }

    private void attemptAegisId(final AlertDialog dialog, EditText emailField,
            EditText passphraseField, boolean createAccount) {
        Context context = dialog.getContext();
        String email = emailField.getText().toString().trim();
        String passphrase = passphraseField.getText().toString();
        if (email.isEmpty() || passphrase.isEmpty()) {
            Toast.makeText(context, "Enter your email and passphrase.", Toast.LENGTH_SHORT).show();
            return;
        }
        setSignInDialogBusy(dialog, true);
        dialog.setTitle("Working\u2026");
        AegisIdManager.Callback callback = new AegisIdManager.Callback() {
            @Override
            public void onResult(boolean ok, String errorOrSummary) {
                setSignInDialogBusy(dialog, false);
                if (ok) {
                    dialog.dismiss();
                } else {
                    dialog.setTitle("Aegis ID");
                    Toast.makeText(context, errorOrSummary, Toast.LENGTH_LONG).show();
                }
                refreshIdentityState();
            }
        };
        if (createAccount) {
            mIdManager.createAccount(email, passphrase, callback);
        } else {
            mIdManager.signIn(email, passphrase, callback);
        }
    }

    private void setSignInDialogBusy(AlertDialog dialog, boolean busy) {
        Button signIn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button create = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (signIn != null) {
            signIn.setEnabled(!busy);
        }
        if (create != null) {
            create.setEnabled(!busy);
        }
    }

    /**
     * Lists the devices linked to the account as "name - platform" rows, each with a
     * Revoke button, in a simple ScrollView (no RecyclerView). Revoking
     * refreshes the list from the backend.
     */
    private void showDevicesDialog() {
        Context context = requireContext();
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dialogPadding(context);
        list.setPadding(pad, pad, pad, pad);

        TextView loading = new TextView(context);
        loading.setText("Loading\u2026");
        list.addView(loading);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(list);

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Manage devices")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .create();
        dialog.show();
        loadDevicesInto(dialog, list);
    }

    private void loadDevicesInto(final AlertDialog dialog, final LinearLayout list) {
        mIdManager.devices(new AegisIdManager.Callback() {
            @Override
            public void onResult(boolean ok, String errorOrSummary) {
                if (!dialog.isShowing()) {
                    return;
                }
                if (ok) {
                    populateDeviceRows(dialog, list, errorOrSummary);
                } else {
                    list.removeAllViews();
                    TextView error = new TextView(dialog.getContext());
                    error.setText(errorOrSummary);
                    list.addView(error);
                }
            }
        });
    }

    private void populateDeviceRows(AlertDialog dialog, LinearLayout list, String devicesJson) {
        list.removeAllViews();
        try {
            JSONArray devices = new JSONArray(devicesJson);
            for (int i = 0; i < devices.length(); i++) {
                list.addView(buildDeviceRow(dialog, list, devices.getJSONObject(i)));
            }
        } catch (JSONException e) {
            TextView error = new TextView(dialog.getContext());
            error.setText("Unexpected devices response.");
            list.addView(error);
        }
    }

    private View buildDeviceRow(
            final AlertDialog dialog, final LinearLayout list, JSONObject device) {
        Context context = dialog.getContext();
        final String id = device.optString("id", "");
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int pad = dialogPadding(context) / 2;
        row.setPadding(0, pad, 0, pad);

        TextView label = new TextView(context);
        label.setText(device.optString("name", "Unknown device") + " \u2014 "
                + device.optString("platform", ""));
        row.addView(label, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button revoke = new Button(context);
        revoke.setText("Revoke");
        revoke.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                revoke.setEnabled(false);
                mIdManager.revokeDevice(id, new AegisIdManager.Callback() {
                    @Override
                    public void onResult(boolean ok, String errorOrSummary) {
                        if (ok) {
                            loadDevicesInto(dialog, list);
                        } else {
                            revoke.setEnabled(true);
                            Toast.makeText(context, errorOrSummary, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
        row.addView(revoke);
        return row;
    }

    private void confirmSignOut() {
        Context context = requireContext();
        new AlertDialog.Builder(context)
                .setTitle("Sign out")
                .setMessage("Sign out of Aegis ID on this device? Your synced data stays "
                        + "on the server and can be restored by signing in again.")
                .setPositiveButton("Sign out", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        mIdManager.signOut(new AegisIdManager.Callback() {
                            @Override
                            public void onResult(boolean ok, String errorOrSummary) {
                                refreshIdentityState();
                            }
                        });
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static int dialogPadding(Context context) {
        return (int) (16 * context.getResources().getDisplayMetrics().density);
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
