/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.email.activity.setup;

import com.android.email.Email;
import com.android.email.EmailAddressValidator;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.activity.Debug;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Prompts the user for the email address and password. Also prompts for
 * "Use this account as default" if this is the 2nd+ account being set up.
 * Attempts to lookup default settings for the domain the user specified. If the
 * domain is known the settings are handed off to the AccountSetupCheckSettings
 * activity. If no settings are found the settings are handed off to the
 * AccountSetupAccountType activity.
 */
public class AccountSetupBasics extends Activity
        implements OnClickListener, TextWatcher {
    private final static boolean ENTER_DEBUG_SCREEN = true;

    private final static String EXTRA_ACCOUNT = "com.android.email.AccountSetupBasics.account";
    private final static String EXTRA_EAS_FLOW = "com.android.email.extra.eas_flow";

    private final static int DIALOG_NOTE = 1;
    private final static int DIALOG_DUPLICATE_ACCOUNT = 2;

    private final static String STATE_KEY_PROVIDER =
        "com.android.email.AccountSetupBasics.provider";

    // NOTE: If you change this value, confirm that the new interval exists in arrays.xml
    private final static int DEFAULT_ACCOUNT_CHECK_INTERVAL = 15;

    private EditText mEmailView;
    private EditText mPasswordView;
    private CheckBox mDefaultView;
    private Button mNextButton;
    private Button mManualSetupButton;
    private EmailContent.Account mAccount;
    private Provider mProvider;
    private boolean mEasFlowMode;
    private String mDuplicateAccountName;

    private EmailAddressValidator mEmailValidator = new EmailAddressValidator();

    public static void actionNewAccount(Activity fromActivity) {
        Intent i = new Intent(fromActivity, AccountSetupBasics.class);
        fromActivity.startActivity(i);
    }

    /**
     * This creates an intent that can be used to start a self-contained account creation flow
     * for exchange accounts.
     */
    public static Intent actionSetupExchangeIntent(Context context) {
        Intent i = new Intent(context, AccountSetupBasics.class);
        i.putExtra(EXTRA_EAS_FLOW, true);
        return i;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.account_setup_basics);
        mEmailView = (EditText)findViewById(R.id.account_email);
        mPasswordView = (EditText)findViewById(R.id.account_password);
        mDefaultView = (CheckBox)findViewById(R.id.account_default);
        mNextButton = (Button)findViewById(R.id.next);
        mManualSetupButton = (Button)findViewById(R.id.manual_setup);

        mNextButton.setOnClickListener(this);
        mManualSetupButton.setOnClickListener(this);

        mEmailView.addTextChangedListener(this);
        mPasswordView.addTextChangedListener(this);

        // Find out how many accounts we have, and if there one or more, then we have a choice
        // about being default or not.
        Cursor c = null;
        try {
            c = getContentResolver().query(
                    EmailContent.Account.CONTENT_URI,
                    EmailContent.Account.ID_PROJECTION,
                    null, null, null);
            if (c.getCount() > 0) {
                mDefaultView.setVisibility(View.VISIBLE);
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }

        mEasFlowMode = getIntent().getBooleanExtra(EXTRA_EAS_FLOW, false);
        if (mEasFlowMode) {
            // No need for manual button -> next is appropriate
            mManualSetupButton.setVisibility(View.GONE);
            // Swap welcome text for EAS-specific text
            TextView welcomeView = (TextView) findViewById(R.id.instructions);
            welcomeView.setText(R.string.accounts_welcome_exchange);
        }

        if (savedInstanceState != null && savedInstanceState.containsKey(EXTRA_ACCOUNT)) {
            mAccount = (EmailContent.Account)savedInstanceState.getParcelable(EXTRA_ACCOUNT);
        }

        if (savedInstanceState != null && savedInstanceState.containsKey(STATE_KEY_PROVIDER)) {
            mProvider = (Provider)savedInstanceState.getSerializable(STATE_KEY_PROVIDER);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        validateFields();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(EXTRA_ACCOUNT, mAccount);
        if (mProvider != null) {
            outState.putSerializable(STATE_KEY_PROVIDER, mProvider);
        }
    }

    public void afterTextChanged(Editable s) {
        validateFields();
    }

    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    private void validateFields() {
        boolean valid = Utility.requiredFieldValid(mEmailView)
                && Utility.requiredFieldValid(mPasswordView)
                && mEmailValidator.isValid(mEmailView.getText().toString().trim());
        mNextButton.setEnabled(valid);
        mManualSetupButton.setEnabled(valid);
        /*
         * Dim the next button's icon to 50% if the button is disabled.
         * TODO this can probably be done with a stateful drawable. Check into it.
         * android:state_enabled
         */
        Utility.setCompoundDrawablesAlpha(mNextButton, mNextButton.isEnabled() ? 255 : 128);
    }

    private String getOwnerName() {
        String name = null;
/* TODO figure out another way to get the owner name
        String projection[] = {
            ContactMethods.NAME
        };
        Cursor c = getContentResolver().query(
                Uri.withAppendedPath(Contacts.People.CONTENT_URI, "owner"), projection, null, null,
                null);
        if (c != null) {
            if (c.moveToFirst()) {
                name = c.getString(0);
            }
            c.close();
        }
*/

        if (name == null || name.length() == 0) {
            long defaultId = Account.getDefaultAccountId(this);
            if (defaultId != -1) {
                Account account = Account.restoreAccountWithId(this, defaultId);
                if (account != null) {
                    name = account.getSenderName();
                }
            }
        }
        return name;
    }

    @Override
    public Dialog onCreateDialog(int id) {
        if (id == DIALOG_NOTE) {
            if (mProvider != null && mProvider.note != null) {
                return new AlertDialog.Builder(this)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setTitle(android.R.string.dialog_alert_title)
                    .setMessage(mProvider.note)
                    .setPositiveButton(
                            getString(R.string.okay_action),
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    finishAutoSetup();
                                }
                            })
                    .setNegativeButton(
                            getString(R.string.cancel_action),
                            null)
                    .create();
            }
        } else if (id == DIALOG_DUPLICATE_ACCOUNT) {
            return new AlertDialog.Builder(this)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(R.string.account_duplicate_dlg_title)
                .setMessage(getString(R.string.account_duplicate_dlg_message_fmt,
                        mDuplicateAccountName))
                .setPositiveButton(R.string.okay_action,
                        new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        dismissDialog(DIALOG_DUPLICATE_ACCOUNT);
                    }
                })
                .create();
        }
        return null;
    }

    /**
     * Update a cached dialog with current values (e.g. account name)
     */
    @Override
    public void onPrepareDialog(int id, Dialog dialog) {
        switch (id) {
            case DIALOG_NOTE:
                if (mProvider != null && mProvider.note != null) {
                    AlertDialog alert = (AlertDialog) dialog;
                    alert.setMessage(mProvider.note);
                }
                break;
            case DIALOG_DUPLICATE_ACCOUNT:
                if (mDuplicateAccountName != null) {
                    AlertDialog alert = (AlertDialog) dialog;
                    alert.setMessage(getString(R.string.account_duplicate_dlg_message_fmt,
                            mDuplicateAccountName));
                }
                break;
        }
    }

    private void finishAutoSetup() {
        String email = mEmailView.getText().toString().trim();
        String password = mPasswordView.getText().toString().trim();
        String[] emailParts = email.split("@");
        String user = emailParts[0];
        String domain = emailParts[1];
        URI incomingUri = null;
        URI outgoingUri = null;
        try {
            String incomingUsername = mProvider.incomingUsernameTemplate;
            incomingUsername = incomingUsername.replaceAll("\\$email", email);
            incomingUsername = incomingUsername.replaceAll("\\$user", user);
            incomingUsername = incomingUsername.replaceAll("\\$domain", domain);

            URI incomingUriTemplate = mProvider.incomingUriTemplate;
            incomingUri = new URI(incomingUriTemplate.getScheme(), incomingUsername + ":"
                    + password, incomingUriTemplate.getHost(), incomingUriTemplate.getPort(),
                    incomingUriTemplate.getPath(), null, null);

            String outgoingUsername = mProvider.outgoingUsernameTemplate;
            outgoingUsername = outgoingUsername.replaceAll("\\$email", email);
            outgoingUsername = outgoingUsername.replaceAll("\\$user", user);
            outgoingUsername = outgoingUsername.replaceAll("\\$domain", domain);

            URI outgoingUriTemplate = mProvider.outgoingUriTemplate;
            outgoingUri = new URI(outgoingUriTemplate.getScheme(), outgoingUsername + ":"
                    + password, outgoingUriTemplate.getHost(), outgoingUriTemplate.getPort(),
                    outgoingUriTemplate.getPath(), null, null);

            // Stop here if the login credentials duplicate an existing account
            mDuplicateAccountName = Utility.findDuplicateAccount(this, -1,
                    incomingUri.getHost(), incomingUsername);
            if (mDuplicateAccountName != null) {
                this.showDialog(DIALOG_DUPLICATE_ACCOUNT);
                return;
            }

        } catch (URISyntaxException use) {
            /*
             * If there is some problem with the URI we give up and go on to
             * manual setup.
             */
            onManualSetup();
            return;
        }

        mAccount = new EmailContent.Account();
        mAccount.setSenderName(getOwnerName());
        mAccount.setEmailAddress(email);
        mAccount.setStoreUri(this, incomingUri.toString());
        mAccount.setSenderUri(this, outgoingUri.toString());
/* TODO figure out the best way to implement this concept
        mAccount.setDraftsFolderName(getString(R.string.special_mailbox_name_drafts));
        mAccount.setTrashFolderName(getString(R.string.special_mailbox_name_trash));
        mAccount.setOutboxFolderName(getString(R.string.special_mailbox_name_outbox));
        mAccount.setSentFolderName(getString(R.string.special_mailbox_name_sent));
*/
        if (incomingUri.toString().startsWith("imap")) {
            // Delete policy must be set explicitly, because IMAP does not provide a UI selection
            // for it. This logic needs to be followed in the auto setup flow as well.
            mAccount.setDeletePolicy(EmailContent.Account.DELETE_POLICY_ON_DELETE);
        }
        mAccount.setSyncInterval(DEFAULT_ACCOUNT_CHECK_INTERVAL);
        AccountSetupCheckSettings.actionCheckSettings(this, mAccount, true, true);
    }

    private void onNext() {
        // If this is EAS flow, don't try to find a provider for the domain!
        if (!mEasFlowMode) {
            String email = mEmailView.getText().toString().trim();
            String[] emailParts = email.split("@");
            String domain = emailParts[1].trim();
            mProvider = findProviderForDomain(domain);
            if (mProvider != null) {
                if (mProvider.note != null) {
                    showDialog(DIALOG_NOTE);
                } else {
                    finishAutoSetup();
                }
                return;
            }
        }
        // Can't use auto setup
        onManualSetup();
    }

    /**
     * This is used in automatic setup mode to jump directly down to the names screen.
     *
     * NOTE:  With this organization, it is *not* possible to auto-create an exchange account,
     * because certain necessary actions happen during AccountSetupOptions (which we are
     * skipping here).
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            String email = mAccount.getEmailAddress();
            boolean isDefault = mDefaultView.isChecked();
            mAccount.setDisplayName(email);
            mAccount.setDefaultAccount(isDefault);
            // At this point we write the Account object to the DB for the first time.
            // From now on we'll only pass the accountId around.
            mAccount.save(this);
            Email.setServicesEnabled(this);
            AccountSetupNames.actionSetNames(this, mAccount.mId, false);
            finish();
        }
    }

    private void onManualSetup() {
        String email = mEmailView.getText().toString().trim();
        String password = mPasswordView.getText().toString().trim();
        String[] emailParts = email.split("@");
        String user = emailParts[0].trim();
        String domain = emailParts[1].trim();

        // Alternate entry to the debug options screen (for devices without a physical keyboard:
        //  Username: d@d.d
        //  Password: debug
        if (ENTER_DEBUG_SCREEN && "d@d.d".equals(email) && "debug".equals(password)) {
            mEmailView.setText("");
            mPasswordView.setText("");
            startActivity(new Intent(this, Debug.class));
            return;
        }

        mAccount = new EmailContent.Account();
        mAccount.setSenderName(getOwnerName());
        mAccount.setEmailAddress(email);
        try {
            URI uri = new URI("placeholder", user + ":" + password, domain, -1, null, null, null);
            mAccount.setStoreUri(this, uri.toString());
            mAccount.setSenderUri(this, uri.toString());
        } catch (URISyntaxException use) {
            // If we can't set up the URL, don't continue - account setup pages will fail too
            Toast.makeText(this, R.string.account_setup_username_password_toast, Toast.LENGTH_LONG)
                    .show();
            mAccount = null;
            return;
        }
/* TODO figure out the best way to implement this concept
        mAccount.setDraftsFolderName(getString(R.string.special_mailbox_name_drafts));
        mAccount.setTrashFolderName(getString(R.string.special_mailbox_name_trash));
        mAccount.setOutboxFolderName(getString(R.string.special_mailbox_name_outbox));
        mAccount.setSentFolderName(getString(R.string.special_mailbox_name_sent));
*/
        mAccount.setSyncInterval(DEFAULT_ACCOUNT_CHECK_INTERVAL);

        AccountSetupAccountType.actionSelectAccountType(this, mAccount, mDefaultView.isChecked(),
                mEasFlowMode);
        finish();
    }

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.next:
                onNext();
                break;
            case R.id.manual_setup:
                onManualSetup();
                break;
        }
    }

    /**
     * Attempts to get the given attribute as a String resource first, and if it fails
     * returns the attribute as a simple String value.
     * @param xml
     * @param name
     * @return the requested resource
     */
    private String getXmlAttribute(XmlResourceParser xml, String name) {
        int resId = xml.getAttributeResourceValue(null, name, 0);
        if (resId == 0) {
            return xml.getAttributeValue(null, name);
        }
        else {
            return getString(resId);
        }
    }

    /**
     * Search the list of known Email providers looking for one that matches the user's email
     * domain.  We look in providers_product.xml first, followed by the entries in
     * platform providers.xml.  This provides a nominal override capability.
     *
     * A match is defined as any provider entry for which the "domain" attribute matches.
     *
     * @param domain The domain portion of the user's email address
     * @return suitable Provider definition, or null if no match found
     */
    private Provider findProviderForDomain(String domain) {
        Provider p = findProviderForDomain(domain, R.xml.providers_product);
        if (p == null) {
            p = findProviderForDomain(domain, R.xml.providers);
        }
        return p;
    }

    /**
     * Search a single resource containing known Email provider definitions.
     *
     * @param domain The domain portion of the user's email address
     * @param resourceId Id of the provider resource to scan
     * @return suitable Provider definition, or null if no match found
     */
    private Provider findProviderForDomain(String domain, int resourceId) {
        try {
            XmlResourceParser xml = getResources().getXml(resourceId);
            int xmlEventType;
            Provider provider = null;
            while ((xmlEventType = xml.next()) != XmlResourceParser.END_DOCUMENT) {
                if (xmlEventType == XmlResourceParser.START_TAG
                        && "provider".equals(xml.getName())
                        && domain.equalsIgnoreCase(getXmlAttribute(xml, "domain"))) {
                    provider = new Provider();
                    provider.id = getXmlAttribute(xml, "id");
                    provider.label = getXmlAttribute(xml, "label");
                    provider.domain = getXmlAttribute(xml, "domain");
                    provider.note = getXmlAttribute(xml, "note");
                }
                else if (xmlEventType == XmlResourceParser.START_TAG
                        && "incoming".equals(xml.getName())
                        && provider != null) {
                    provider.incomingUriTemplate = new URI(getXmlAttribute(xml, "uri"));
                    provider.incomingUsernameTemplate = getXmlAttribute(xml, "username");
                }
                else if (xmlEventType == XmlResourceParser.START_TAG
                        && "outgoing".equals(xml.getName())
                        && provider != null) {
                    provider.outgoingUriTemplate = new URI(getXmlAttribute(xml, "uri"));
                    provider.outgoingUsernameTemplate = getXmlAttribute(xml, "username");
                }
                else if (xmlEventType == XmlResourceParser.END_TAG
                        && "provider".equals(xml.getName())
                        && provider != null) {
                    return provider;
                }
            }
        }
        catch (Exception e) {
            Log.e(Email.LOG_TAG, "Error while trying to load provider settings.", e);
        }
        return null;
    }

    static class Provider implements Serializable {
        private static final long serialVersionUID = 8511656164616538989L;

        public String id;

        public String label;

        public String domain;

        public URI incomingUriTemplate;

        public String incomingUsernameTemplate;

        public URI outgoingUriTemplate;

        public String outgoingUsernameTemplate;

        public String note;
    }
}
