package com.lynxit.contactswrapper;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.Email;

import java.util.*;


import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;


public class ContactsWrapper extends ReactContextBaseJavaModule implements ActivityEventListener {

    private static final int CONTACT_REQUEST = 1;
    private static final int EMAIL_REQUEST = 2;

    private static final String E_CONTACT_CANCELLED = "E_CONTACT_CANCELLED";
    private static final String E_CONTACT_NO_DATA = "E_CONTACT_NO_DATA";
    private static final String E_CONTACT_NO_EMAIL = "E_CONTACT_NO_EMAIL";
    private static final String E_CONTACT_EXCEPTION = "E_CONTACT_EXCEPTION";
    private static final String E_CONTACT_PERMISSION = "E_CONTACT_PERMISSION";

    private static final String KEY_FIRST_NAME = "firstName";
    private static final String KEY_LAST_NAME = "lastName";
    private static final String KEY_PHONES = "phoneNumbers";
    private static final String KEY_CITY = "city";
    private static final String KEY_ADDRESSES = "postalAddresses";
    private static final String KEY_LABEL = "label";
    private static final String KEY_EMAIL = "emailAddresses";


    private Promise mContactsPromise;
    private Activity mCtx;
    private final ContentResolver contentResolver;

    public ContactsWrapper(ReactApplicationContext reactContext) {
        super(reactContext);
        this.contentResolver = getReactApplicationContext().getContentResolver();
        reactContext.addActivityEventListener(this);
    }

    @Override
    public String getName() {
        return "ContactsWrapper";
    }



    @ReactMethod
    public void getContact(Promise contactsPromise) {
        launchPicker(contactsPromise, CONTACT_REQUEST);
    }

    @ReactMethod
    public void getEmail(Promise contactsPromise) {
        launchPicker(contactsPromise, EMAIL_REQUEST);
    }

    /**
     * Lanch the contact picker, with the specified requestCode for returned data.
     * @param contactsPromise - promise passed in from React Native.
     * @param requestCode - request code to specify what contact data to return
     */
    private void launchPicker(Promise contactsPromise, int requestCode) {
//        this.contentResolver.query(Uri.parse("content://com.android.contacts/contacts/lookup/0r3-A7416BA07AEA92F2/3"), null, null, null, null);
        Cursor cursor = this.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null);
        if (cursor != null) {
            mContactsPromise = contactsPromise;
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setType(ContactsContract.Contacts.CONTENT_TYPE);
            mCtx = getCurrentActivity();
            if (intent.resolveActivity(mCtx.getPackageManager()) != null) {
                mCtx.startActivityForResult(intent, requestCode);
            }
            cursor.close();
        }else{
            mContactsPromise.reject(E_CONTACT_PERMISSION, "no permission");
        }
    }

    @Override
    public void onActivityResult(Activity ContactsWrapper, final int requestCode, final int resultCode, final Intent intent) {

        if(mContactsPromise == null || mCtx == null
                || (requestCode != CONTACT_REQUEST && requestCode != EMAIL_REQUEST)){
            return;
        }

        switch (resultCode) {
            case (Activity.RESULT_OK):
                Uri contactUri = intent.getData();
                switch(requestCode) {
                    case(CONTACT_REQUEST):
                        try {
                            /* Retrieve all possible data about contact and return as a JS object */

                            //First get ID
                            String id = null;
                            int idx;
                            WritableMap contactData = Arguments.createMap();
                            Cursor cursor = this.contentResolver.query(contactUri, null, null, null, null);
                            if (cursor != null && cursor.moveToFirst()) {
                                idx = cursor.getColumnIndex(ContactsContract.Contacts._ID);
                                id = cursor.getString(idx);
                            } else {
                                mContactsPromise.reject(E_CONTACT_NO_DATA, "Contact Data Not Found");
                                return;
                            }

                            // Build the Entity URI.
                            Uri.Builder b = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, id).buildUpon();
                            b.appendPath(ContactsContract.Contacts.Entity.CONTENT_DIRECTORY);
                            contactUri = b.build();

                            boolean foundData = readContact(contactUri, contactData);

                            if(foundData) {
                                mContactsPromise.resolve(contactData);
                                return;
                            } else {
                                mContactsPromise.reject(E_CONTACT_NO_DATA, "No data found for contact");
                                return;
                            }
                        } catch (Exception e) {
                            mContactsPromise.reject(E_CONTACT_EXCEPTION, e.getMessage());
                            return;
                        }
                        /* No need to break as all paths return */
                    case(EMAIL_REQUEST):
                        /* Return contacts first email address, as string */
                        try {


                            // get the contact id from the Uri
                            String id = contactUri.getLastPathSegment();

                            // query for everything email
                            Cursor cursor = mCtx.getContentResolver().query(Email.CONTENT_URI,
                                    null, Email.CONTACT_ID + "=?", new String[]{id},
                                    null);

                            int emailIdx = cursor.getColumnIndex(Email.DATA);
                            String email;
                            // For now, return only the first email address, as a string
                            if (cursor.moveToFirst()) {
                                email = cursor.getString(emailIdx);
                                mContactsPromise.resolve(email);
                                return;
                            } else {
                                //Contact has no email address stored
                                mContactsPromise.reject(E_CONTACT_NO_EMAIL, "No email found for contact");
                                return;
                            }
                        } catch (Exception e) {
                            mContactsPromise.reject(E_CONTACT_EXCEPTION, e.getMessage());
                            return;
                        }
                        /* No need to break as all paths return */
                    default:
                        //Unexpected return code - shouldn't happen, but catch just in case
                        mContactsPromise.reject(E_CONTACT_EXCEPTION, "Unexpected error in request");
                        return;
                }
            default:
                //Request was cancelled
                mContactsPromise.reject(E_CONTACT_CANCELLED, "Cancelled");
                return;
        }
    }

    private boolean readContact(Uri contactUri, WritableMap contactData) {
        String[] projection = {
                ContactsContract.Contacts.Entity.MIMETYPE,
                ContactsContract.Contacts.Entity.DATA1,
                StructuredName.GIVEN_NAME,
                StructuredName.FAMILY_NAME
        };
        String sortOrder = ContactsContract.Contacts.Entity.RAW_CONTACT_ID + " ASC";
        Cursor cursor = this.contentResolver.query(contactUri, projection, null, null, sortOrder);
        if(cursor == null)  return false;


        String mime;
        boolean foundData = false;

        int dataIdx = cursor.getColumnIndex(ContactsContract.Contacts.Entity.DATA1);
        int mimeIdx = cursor.getColumnIndex(ContactsContract.Contacts.Entity.MIMETYPE);
        if (cursor.moveToFirst()) {
            WritableArray phoneNumbers = Arguments.createArray();
            WritableArray emailAddresses = Arguments.createArray();
            WritableArray postalAddress = Arguments.createArray();
            WritableMap address = Arguments.createMap();
            do {
                mime = cursor.getString(mimeIdx);
                if (mime.equals(CommonDataKinds.Phone.CONTENT_ITEM_TYPE)) {
                    phoneNumbers.pushString(cursor.getString(dataIdx));
                    foundData = true;
                } else if (mime.equals(Email.CONTENT_ITEM_TYPE)) {
                    emailAddresses.pushString(cursor.getString(dataIdx));
                    foundData = true;
                } else if (mime.equals(CommonDataKinds.Note.CONTENT_ITEM_TYPE)) {
                    contactData.putString(KEY_LABEL, cursor.getString(dataIdx));
                    foundData = true;
                } else if (mime.equals(CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)) {
                    address.putString(KEY_CITY, cursor.getString(dataIdx));
                    postalAddress.pushMap(address);
                    foundData = true;
                } else if (mime.equals(StructuredName.CONTENT_ITEM_TYPE)) {
                    String given = cursor.getString(cursor.getColumnIndex(StructuredName.GIVEN_NAME));
                    String family = cursor.getString(cursor.getColumnIndex(StructuredName.FAMILY_NAME));
                    contactData.putString(KEY_FIRST_NAME, given);
                    contactData.putString(KEY_LAST_NAME, family);
                    foundData = true;
                }

            } while (cursor.moveToNext());

            contactData.putArray(KEY_PHONES, phoneNumbers);
            contactData.putArray(KEY_EMAIL, emailAddresses);
            contactData.putArray(KEY_ADDRESSES, postalAddress);

        }
        cursor.close();
        return foundData;
    }

    public void onNewIntent(Intent intent) {

    }
}