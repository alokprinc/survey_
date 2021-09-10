/*
 * Copyright (C) 2012-2013 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.opendatakit.survey.activities;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore.Images;
import android.widget.Toast;

import org.apache.commons.io.FileUtils;
import org.opendatakit.activities.BaseActivity;
import org.opendatakit.consts.IntentConsts;
import org.opendatakit.logging.WebLogger;
import org.opendatakit.survey.R;
import org.opendatakit.utilities.MediaUtils;
import org.opendatakit.utilities.ODKFileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Simple shim for media interactions.
 *
 * @author mitchellsundt@gmail.com
 *
 */
public class MediaChooseImageActivity extends BaseActivity {
  private static final String t = "MediaChooseImageActivity";
  private static final int ACTION_CODE = 1;
  private static final String MEDIA_CLASS = "image/";

  private static final String URI_FRAGMENT_NEW_FILE_BASE = "uriFragmentNewFileBase";

  private String appName = null;
  private String tableId = null;
  private String instanceId = null;
  private String uriFragmentNewFileBase = null;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    Bundle extras = getIntent().getExtras();
    if (extras != null) {
      appName = extras.getString(IntentConsts.INTENT_KEY_APP_NAME);
      tableId = extras.getString(IntentConsts.INTENT_KEY_TABLE_ID);
      instanceId = extras.getString(IntentConsts.INTENT_KEY_INSTANCE_ID);
      uriFragmentNewFileBase = extras.getString(URI_FRAGMENT_NEW_FILE_BASE);
    }

    if (savedInstanceState != null) {
      appName = savedInstanceState.getString(IntentConsts.INTENT_KEY_APP_NAME);
      tableId = savedInstanceState.getString(IntentConsts.INTENT_KEY_TABLE_ID);
      instanceId = savedInstanceState.getString(IntentConsts.INTENT_KEY_INSTANCE_ID);
      uriFragmentNewFileBase = savedInstanceState.getString(URI_FRAGMENT_NEW_FILE_BASE);
    }

    if (appName == null) {
      throw new IllegalArgumentException("Expected " + IntentConsts.INTENT_KEY_APP_NAME
            + " key in intent bundle. Not found.");
    }

    if (tableId == null) {
      throw new IllegalArgumentException("Expected " + IntentConsts.INTENT_KEY_TABLE_ID
              + " key in intent bundle. Not found.");
    }
    if (instanceId == null) {
      throw new IllegalArgumentException("Expected " + IntentConsts.INTENT_KEY_INSTANCE_ID
              + " key in intent bundle. Not found.");
    }

    // On Nexus 6 running 6.0.1, the directory needs to exist before the capture.
    // need to ensure that the directory exists before we launch the camera app.
    if ( !ODKFileUtils.createFolder(ODKFileUtils.getInstanceFolder(appName, tableId, instanceId)) ) {
      Toast.makeText(this, R.string.media_save_failed, Toast.LENGTH_SHORT).show();
      // keep the image as a captured image so user can choose it.
      setResult(Activity.RESULT_CANCELED);
      finish();
      return;
    }

    if (uriFragmentNewFileBase == null) {
      throw new IllegalArgumentException("Expected " + URI_FRAGMENT_NEW_FILE_BASE
          + " key in intent bundle. Not found.");
    }

    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
    i.setType(MEDIA_CLASS + "*");
    try {
      startActivityForResult(i, ACTION_CODE);
    } catch (ActivityNotFoundException e) {
      Toast.makeText(this,
          getString(R.string.activity_not_found, Intent.ACTION_GET_CONTENT + " " + MEDIA_CLASS),
          Toast.LENGTH_SHORT).show();
      setResult(Activity.RESULT_CANCELED);
      finish();
    }
  }
  
  @Override
  public String getAppName() {
    return appName;
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putString(IntentConsts.INTENT_KEY_APP_NAME, appName);
    outState.putString(IntentConsts.INTENT_KEY_TABLE_ID, tableId);
    outState.putString(IntentConsts.INTENT_KEY_INSTANCE_ID, instanceId);
    outState.putString(URI_FRAGMENT_NEW_FILE_BASE, uriFragmentNewFileBase);
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent intent) {
    super.onActivityResult(requestCode, resultCode, intent);

    if (resultCode == Activity.RESULT_CANCELED) {
      // request was canceled -- propagate
      setResult(Activity.RESULT_CANCELED);
      finish();
      return;
    }

    /*
     * We have chosen a saved image from somewhere, but we really want it to be
     * in: /sdcard/odk/instances/[current instance]/something.jpg so we copy it
     * there and insert that copy into the content provider.
     */

    // get gp of chosen file
    Uri selectedMedia = intent.getData();
    if (selectedMedia == null) {
      WebLogger.getLogger(appName).e(t, "Selected Media URI is null");
      Toast.makeText(this, "Selected Media has a null URI",
              Toast.LENGTH_SHORT).show();
      setResult(Activity.RESULT_CANCELED);
      finish();
      return;
    }

    // Get the scheme to determine whether the selected media URI is a content URI
    String scheme = selectedMedia.getScheme();
    if (scheme == null) {
      WebLogger.getLogger(appName).e(t, "Selected Media URI scheme is null");
      Toast.makeText(this, "Selected Media URI has a null scheme",
              Toast.LENGTH_SHORT).show();
      setResult(Activity.RESULT_CANCELED);
      finish();
      return;
    }

    File tempOutputFile = null;
    String sourceMediaPath;
    boolean isContentUri = scheme.equals("content");

    if (isContentUri) {
      try {
        // if the uri is a content uri, copy the contents of the select media to
        // a temp cache dir, and use the absolute path of this temp file
        // as the sourceMediaPath
        InputStream inputContent = getContentResolver().openInputStream(selectedMedia);
        if (inputContent == null) {
          WebLogger.getLogger(appName).e(t, "Unable to open input stream for selected media. " +
                  "Input stream was null");
          Toast.makeText(this, "Input stream for the selected media was null",
                  Toast.LENGTH_SHORT).show();
          setResult(Activity.RESULT_CANCELED);
          finish();
          return;
        }

        // Get the file type of the selected media
        String uriType = getContentResolver().getType(selectedMedia);
        if (uriType == null) {
          WebLogger.getLogger(appName).e(t, "MIME type of selected media is null");
          Toast.makeText(this, "MIME type of selected media is null",
                  Toast.LENGTH_SHORT).show();
          setResult(Activity.RESULT_CANCELED);
          finish();
          return;
        }

        String selectedFileSuffix = "." + uriType.split("/")[1];
        tempOutputFile = File.createTempFile("tempImage", selectedFileSuffix, getCacheDir());
        FileUtils.copyInputStreamToFile(inputContent, tempOutputFile);
        sourceMediaPath = tempOutputFile.getAbsolutePath();
        if (sourceMediaPath == null) {
          WebLogger.getLogger(appName).e(t, "Source Media Path for the selected media (with " +
                  "content URI) was null");
          Toast.makeText(this, "Unable to get path for selected media with Content URI",
                  Toast.LENGTH_SHORT).show();
          setResult(Activity.RESULT_CANCELED);
          finish();
          return;
        }
      } catch (Exception e) {
        WebLogger.getLogger(appName).e(t, "Failed to copy selected media with content URI to " +
                        "a temporary output file");
        Toast.makeText(this, "Copy of media attachment failed",
                Toast.LENGTH_SHORT).show();
        setResult(Activity.RESULT_CANCELED);
        finish();
        return;
      }
    } else {
      sourceMediaPath = MediaUtils.getPathFromUri(this, selectedMedia, Images.Media.DATA);
    }

    File sourceMedia = new File(sourceMediaPath);
    String extension = sourceMediaPath.substring(sourceMediaPath.lastIndexOf("."));

    File newMedia = ODKFileUtils.getRowpathFile(appName, tableId, instanceId, uriFragmentNewFileBase + extension);
    try {
      ODKFileUtils.copyFile(sourceMedia, newMedia);
    } catch (IOException e) {
      WebLogger.getLogger(appName).e(t, "Failed to copy " + sourceMedia.getAbsolutePath());
      Toast.makeText(this, R.string.media_save_failed, Toast.LENGTH_SHORT).show();
      // keep the image as a captured image so user can choose it.
      setResult(Activity.RESULT_CANCELED);
      finish();
      return;
    } finally {
      // delete the tempOutputFile if created
      if (tempOutputFile != null) {
        if (!tempOutputFile.delete()) {
          WebLogger.getLogger(appName).e(t, "Failed to delete " + tempOutputFile.getAbsolutePath());
        }
      }
    }

    WebLogger.getLogger(appName).i(t, "copied " + sourceMedia.getAbsolutePath() + " to " + newMedia.getAbsolutePath());

    Uri mediaURI = null;
    if (newMedia.exists()) {
      // Add the new image to the Media content provider so that the
      // viewing is fast in Android 2.0+
      ContentValues values = new ContentValues(6);
      values.put(Images.Media.TITLE, newMedia.getName());
      values.put(Images.Media.DISPLAY_NAME, newMedia.getName());
      values.put(Images.Media.DATE_TAKEN, System.currentTimeMillis());
      values.put(Images.Media.MIME_TYPE, MEDIA_CLASS + extension.substring(1));
      values.put(Images.Media.DATA, newMedia.getAbsolutePath());

      mediaURI = getContentResolver().insert(Images.Media.EXTERNAL_CONTENT_URI, values);
      WebLogger.getLogger(appName).i(t, "Insert " + MEDIA_CLASS + " returned uri = " + mediaURI.toString());

      // if you are replacing an answer. delete the previous image using
      // the
      // content provider.
      String binarypath = MediaUtils.getPathFromUri(this, mediaURI, Images.Media.DATA);
      File newMediaFromCP = new File(binarypath);

      WebLogger.getLogger(appName).i(t, "Return mediaFile: " + newMediaFromCP.getAbsolutePath());
      Intent i = new Intent();
      i.putExtra(IntentConsts.INTENT_KEY_URI_FRAGMENT, ODKFileUtils.asRowpathUri(appName, tableId, instanceId, newMediaFromCP));
      String name = newMediaFromCP.getName();
      i.putExtra(IntentConsts.INTENT_KEY_CONTENT_TYPE, MEDIA_CLASS + name.substring(name.lastIndexOf(".") + 1));
      setResult(Activity.RESULT_OK, i);
      finish();
    } else {
      WebLogger.getLogger(appName).e(t, "No " + MEDIA_CLASS + " exists at: " + newMedia.getAbsolutePath());
      Toast.makeText(this, R.string.media_save_failed, Toast.LENGTH_SHORT).show();
      setResult(Activity.RESULT_CANCELED);
      finish();
    }
  }

  @Override
  public void databaseAvailable() {
  }

  @Override
  public void databaseUnavailable() {
  }

}
