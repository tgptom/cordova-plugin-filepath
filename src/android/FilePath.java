package com.hiddentao.cordova.filepath;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class FilePath extends CordovaPlugin {

    private static final String TAG = "[FilePath plugin]";

    private static final int INVALID_ACTION_ERROR_CODE = -1;

    private static final int GET_PATH_ERROR_CODE = 0;
    private static final int GET_CLOUD_PATH_ERROR_CODE = 1;

    public void initialize(CordovaInterface cordova, final CordovaWebView webView) {
        super.initialize(cordova, webView);
    }

    /**
     * Executes the request and returns PluginResult.
     *
     * @param action          The action to execute.
     * @param args            JSONArray of arguments for the plugin.
     * @param callbackContext The callback context through which to return stuff to caller.
     * @return A PluginResult object with a status and message.
     */
    @Override
    public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) {
        if (!"resolveNativePath".equals(action)) {
            sendError(callbackContext, INVALID_ACTION_ERROR_CODE, "Invalid action.");
            return false;
        }

        final String uriStr = args.optString(0, null);
        if (TextUtils.isEmpty(uriStr)) {
            sendError(callbackContext, GET_PATH_ERROR_CODE, "Unable to resolve filesystem path.");
            return true;
        }

        cordova.getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                resolveNativePath(uriStr, callbackContext);
            }
        });

        return true;
    }

    private void resolveNativePath(String uriStr, CallbackContext callbackContext) {
        Uri uri = Uri.parse(uriStr);

        Log.d(TAG, "URI: " + uriStr);

        Context appContext = this.cordova.getActivity().getApplicationContext();
        ResolvedPath resolvedPath = getPath(appContext, uri);

        if (resolvedPath == null || TextUtils.isEmpty(resolvedPath.path)) {
            int code = isCloudUri(uri) ? GET_CLOUD_PATH_ERROR_CODE : GET_PATH_ERROR_CODE;
            String message = isCloudUri(uri)
                    ? "Files from cloud cannot be resolved to filesystem and could not be copied to cache."
                    : "Unable to resolve filesystem path.";
            sendError(callbackContext, code, message);
            return;
        }

        Log.d(TAG, "Filepath: " + resolvedPath.path);
        callbackContext.success("file://" + resolvedPath.path);
    }

    private void sendError(CallbackContext callbackContext, int code, String message) {
        JSONObject resultObj = new JSONObject();
        try {
            resultObj.put("code", code);
            resultObj.put("message", message == null ? "Unknown error." : message);
        } catch (Exception e) {
            Log.e(TAG, "Unable to build error response", e);
        }
        callbackContext.error(resultObj);
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     */
    private static boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    private static boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     */
    private static boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is Google Photos.
     */
    private static boolean isGooglePhotosUri(Uri uri) {
        return ("com.google.android.apps.photos.content".equals(uri.getAuthority())
                || "com.google.android.apps.photos.contentprovider".equals(uri.getAuthority()));
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is Google Drive.
     */
    private static boolean isGoogleDriveUri(Uri uri) {
        return "com.google.android.apps.docs.storage".equals(uri.getAuthority()) || "com.google.android.apps.docs.storage.legacy".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is One Drive.
     */
    private static boolean isOneDriveUri(Uri uri) {
        return "com.microsoft.skydrive.content.external".equals(uri.getAuthority());
    }

    private static boolean isCloudUri(Uri uri) {
        return isGoogleDriveUri(uri) || isOneDriveUri(uri) || isGooglePhotosUri(uri);
    }

    /**
     * Get the value of the data column for this Uri. This is useful for
     * MediaStore Uris, and other file-based ContentProviders.
     *
     * @param context       The context.
     * @param uri           The Uri to query.
     * @param selection     (Optional) Filter used in the query.
     * @param selectionArgs (Optional) Selection arguments used in the query.
     * @return The value of the _data column, which is typically a file path.
     */
    private static String getDataColumn(Context context, Uri uri, String selection,
                                        String[] selectionArgs) {

        final String column = "_data";
        final String[] projection = {
                column
        };

        try (Cursor cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                final int columnIndex = cursor.getColumnIndex(column);
                if (columnIndex >= 0) {
                    return cursor.getString(columnIndex);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to query data column for URI " + uri + ": " + getSafeMessage(e));
        }
        return null;
    }

    /**
     * Get content:// from segment list
     * In the new Uri Authority of Google Photos, the last segment is not the content:// anymore
     * So let's iterate through all segments and find the content uri!
     *
     * @param segments The list of segment
     */
    private static String getContentFromSegments(List<String> segments) {
        if (segments == null) {
            return "";
        }

        for (String item : segments) {
            if (!TextUtils.isEmpty(item) && item.startsWith("content://")) {
                return item;
            }
        }

        return "";
    }

    /**
     * Check if a file exists on device and can be read.
     *
     * @param filePath The absolute file path
     */
    private static boolean fileExists(String filePath) {
        if (TextUtils.isEmpty(filePath)) {
            return false;
        }

        File file = new File(filePath);

        return file.exists() && file.canRead();
    }

    /**
     * Get full file path from external storage
     *
     * @param pathData The storage type and the relative path
     */
    private static String getPathFromExtSD(String[] pathData) {
        if (pathData == null || pathData.length < 2) {
            return "";
        }

        final String type = pathData[0];
        final String relativePath = pathData[1];

        // on some devices, `type` is a dynamic string like "71F8-2C0A".
        if ("primary".equalsIgnoreCase(type)) {
            String fullPath = new File(Environment.getExternalStorageDirectory(), relativePath).getAbsolutePath();
            if (fileExists(fullPath)) {
                return fullPath;
            }
        }

        String fullPath = new File(new File("/storage", type), relativePath).getAbsolutePath();
        if (fileExists(fullPath)) {
            return fullPath;
        }

        String secondaryStorage = System.getenv("SECONDARY_STORAGE");
        if (!TextUtils.isEmpty(secondaryStorage)) {
            fullPath = new File(secondaryStorage, relativePath).getAbsolutePath();
            if (fileExists(fullPath)) {
                return fullPath;
            }
        }

        String externalStorage = System.getenv("EXTERNAL_STORAGE");
        if (!TextUtils.isEmpty(externalStorage)) {
            fullPath = new File(externalStorage, relativePath).getAbsolutePath();
            if (fileExists(fullPath)) {
                return fullPath;
            }
        }

        return "";
    }

    /**
     * sometimes in raw type, the second part is a valid filepath
     *
     * @param rawPath The raw path
     */
    private static String getRawFilepath(String rawPath) {
        if (TextUtils.isEmpty(rawPath)) {
            return "";
        }

        if (rawPath.startsWith("raw:")) {
            String candidatePath = rawPath.substring("raw:".length());
            if (fileExists(candidatePath)) {
                return candidatePath;
            }
        }

        final String[] split = rawPath.split(":", 2);
        if (split.length == 2 && fileExists(split[1])) {
            return split[1];
        }

        return "";
    }

    private static String getDisplayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    return cursor.getString(nameIndex);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to query display name for URI " + uri + ": " + getSafeMessage(e));
        }

        return null;
    }

    private static String sanitizeFileName(String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return "filepath";
        }

        String cleaned = fileName.replaceAll("[\\\\/:*?\"<>|]", "_").replace("..", "_").trim();
        if (TextUtils.isEmpty(cleaned)) {
            return "filepath";
        }

        return cleaned;
    }

    private static String getFileExtension(String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return "";
        }

        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }

        String extension = fileName.substring(dotIndex);
        return extension.contains(File.separator) ? "" : extension;
    }

    private static String removeExtension(String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return "";
        }

        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return fileName.substring(0, dotIndex);
        }

        return fileName;
    }

    private static String extensionFromMimeType(Context context, Uri uri) {
        String mimeType = context.getContentResolver().getType(uri);
        if (TextUtils.isEmpty(mimeType)) {
            return "";
        }

        String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType.toLowerCase(Locale.ROOT));
        if (TextUtils.isEmpty(extension)) {
            return "";
        }

        return "." + extension;
    }

    private static String copyUriToCache(Context context, Uri uri) {
        String displayName = getDisplayName(context, uri);
        if (TextUtils.isEmpty(displayName)) {
            displayName = uri.getLastPathSegment();
        }

        displayName = sanitizeFileName(displayName);

        String extension = getFileExtension(displayName);
        if (TextUtils.isEmpty(extension)) {
            extension = extensionFromMimeType(context, uri);
        }

        String baseName = sanitizeFileName(removeExtension(displayName));
        if (TextUtils.isEmpty(baseName)) {
            baseName = "filepath";
        }

        File cacheFile = new File(context.getCacheDir(), baseName + "-" + UUID.randomUUID() + extension);

        try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                Log.w(TAG, "Content resolver returned null stream for URI " + uri);
                return null;
            }

            try (OutputStream outputStream = new FileOutputStream(cacheFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                }
                outputStream.flush();
            }

            return cacheFile.getAbsolutePath();
        } catch (Exception e) {
            if (cacheFile.exists() && !cacheFile.delete()) {
                Log.w(TAG, "Unable to clean up cache file " + cacheFile.getAbsolutePath());
            }
            Log.w(TAG, "Failed to copy URI " + uri + " to cache: " + getSafeMessage(e));
            return null;
        }
    }

    /**
     * Get a file path from a Uri. This will get the path for Storage Access
     * Framework Documents, as well as the _data field for the MediaStore and
     * other file-based ContentProviders.
     *
     * @param context The context.
     * @param uri     The Uri to query.
     */
    private static ResolvedPath getPath(final Context context, final Uri uri) {
        if (uri == null) {
            return null;
        }

        Log.d(TAG, "File - " +
                "Authority: " + uri.getAuthority() +
                ", Fragment: " + uri.getFragment() +
                ", Port: " + uri.getPort() +
                ", Query: " + uri.getQuery() +
                ", Scheme: " + uri.getScheme() +
                ", Host: " + uri.getHost() +
                ", Segments: " + uri.getPathSegments()
        );

        if ("file".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            return fileExists(path) ? new ResolvedPath(path) : null;
        }

        if (!"content".equalsIgnoreCase(uri.getScheme())) {
            return null;
        }

        String directPath = null;
        final boolean isKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;

        // DocumentProvider
        if (isKitKat && DocumentsContract.isDocumentUri(context, uri)) {
            if (isExternalStorageDocument(uri)) {
                String docId = getDocumentIdSafely(uri);
                if (!TextUtils.isEmpty(docId)) {
                    directPath = getPathFromExtSD(docId.split(":", 2));
                }
            } else if (isDownloadsDocument(uri)) {
                directPath = resolveDownloadsDocumentPath(context, uri);
            } else if (isMediaDocument(uri)) {
                directPath = resolveMediaDocumentPath(context, uri);
            }
        } else if (isGooglePhotosUri(uri)) {
            if (!uri.toString().contains("mediakey")) {
                String contentPath = getContentFromSegments(uri.getPathSegments());
                if (!TextUtils.isEmpty(contentPath)) {
                    ResolvedPath nestedPath = getPath(context, Uri.parse(contentPath));
                    if (nestedPath != null) {
                        return nestedPath;
                    }
                }
            }
            directPath = getDataColumn(context, uri, null, null);
        } else {
            directPath = getDataColumn(context, uri, null, null);
        }

        if (fileExists(directPath)) {
            return new ResolvedPath(directPath);
        }

        String cachePath = copyUriToCache(context, uri);
        if (fileExists(cachePath)) {
            return new ResolvedPath(cachePath);
        }

        return null;
    }

    private static String resolveDownloadsDocumentPath(Context context, Uri uri) {
        String displayName = getDisplayName(context, uri);
        if (!TextUtils.isEmpty(displayName)) {
            String candidatePath = new File(Environment.getExternalStorageDirectory(), "Download" + File.separator + displayName).getAbsolutePath();
            if (fileExists(candidatePath)) {
                return candidatePath;
            }
        }

        String documentId = getDocumentIdSafely(uri);
        if (TextUtils.isEmpty(documentId)) {
            return null;
        }

        String rawFilepath = getRawFilepath(documentId);
        if (!TextUtils.isEmpty(rawFilepath)) {
            return rawFilepath;
        }

        Long contentId = parseContentId(documentId);
        if (contentId == null) {
            return null;
        }

        String[] contentUriPrefixesToTry = new String[]{
                "content://downloads/public_downloads",
                "content://downloads/my_downloads"
        };

        for (String contentUriPrefix : contentUriPrefixesToTry) {
            try {
                Uri contentUri = ContentUris.withAppendedId(Uri.parse(contentUriPrefix), contentId);
                String path = getDataColumn(context, contentUri, null, null);
                if (fileExists(path)) {
                    return path;
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to resolve downloads URI prefix " + contentUriPrefix + ": " + getSafeMessage(e));
            }
        }

        return null;
    }

    private static String resolveMediaDocumentPath(Context context, Uri uri) {
        String documentId = getDocumentIdSafely(uri);
        if (TextUtils.isEmpty(documentId)) {
            return null;
        }

        String[] split = documentId.split(":", 2);
        if (split.length < 2) {
            return null;
        }

        String type = split[0];
        Uri contentUri;
        if ("image".equals(type)) {
            contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        } else if ("video".equals(type)) {
            contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        } else if ("audio".equals(type)) {
            contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        } else {
            contentUri = MediaStore.Files.getContentUri("external");
        }

        String selection = "_id=?";
        String[] selectionArgs = new String[]{split[1]};

        return getDataColumn(context, contentUri, selection, selectionArgs);
    }

    private static String getDocumentIdSafely(Uri uri) {
        try {
            return DocumentsContract.getDocumentId(uri);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Unable to parse document ID for URI " + uri + ": " + getSafeMessage(e));
            return null;
        }
    }

    private static Long parseContentId(String documentId) {
        if (TextUtils.isEmpty(documentId)) {
            return null;
        }

        String id = documentId;
        if (documentId.contains(":")) {
            String[] split = documentId.split(":", 2);
            if (split.length < 2 || TextUtils.isEmpty(split[1])) {
                return null;
            }
            id = split[1];
        }

        if (TextUtils.isDigitsOnly(id)) {
            try {
                return Long.valueOf(id);
            } catch (NumberFormatException e) {
                Log.w(TAG, "Unable to parse content ID " + id + ": " + getSafeMessage(e));
            }
        }

        return null;
    }

    private static String getSafeMessage(Exception e) {
        if (e == null || TextUtils.isEmpty(e.getMessage())) {
            return "Unexpected error";
        }

        return e.getMessage();
    }

    private static final class ResolvedPath {
        private final String path;

        private ResolvedPath(String path) {
            this.path = path;
        }
    }
}
