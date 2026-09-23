# cordova-plugin-filepath

**PLEASE NOTE: This plugin is no longer actively maintained.**

This plugin allows you to resolve the native filesystem path for Android content
URIs and is based on code in the [aFileChooser](https://github.com/iPaulPro/aFileChooser/blob/master/aFileChooser/src/com/ipaulpro/afilechooser/utils/FileUtils.java) library.

Original inspiration [from StackOverflow](http://stackoverflow.com/questions/20067508/get-real-path-from-uri-android-kitkat-new-storage-access-framework).

## Installation

```bash
$ cordova plugin add cordova-plugin-filepath
```

## Supported Platforms

* Android

## Usage

Once installed the plugin defines the `window.FilePath` object. To resolve a
file path:

```js
window.FilePath.resolveNativePath('content://...', successCallback, errorCallback);
```

##### successCallback
Returns a ``file://`` path.

On modern Android versions, direct filesystem paths are not always available for
`content://` URIs. In those cases this plugin copies the URI contents into the
app cache directory and returns that cache file path instead.

##### errorCallback
Returns the following object:
```js
{ code: <integer>, message: <string> }
```
Possible error codes are:
* ``-1`` - describes an invalid action
* ``0`` - ``file://`` path could not be resolved
* ``1`` - a cloud-backed URI could not be read and copied

## Android scoped-storage notes

* The plugin no longer requests broad media/storage permissions in the manifest.
  It relies on URI grants from the caller for `content://` URIs.
* Cache-copy fallback files are created under the app cache directory. These
  files are app-private and may be removed by the OS at any time.

## Compatibility

Validated with Cordova Android platform versions:

* `cordova-android@14`
* `cordova-android@15`

## LICENSE

Apache (see LICENSE.md)
