package com.pichillilorenzo.flutter_inappwebview.webview.in_app_webview;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.ClientCertRequest;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.HttpAuthHandler;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.webkit.SafeBrowsingResponseCompat;
import androidx.webkit.WebResourceErrorCompat;
import androidx.webkit.WebResourceRequestCompat;
import androidx.webkit.WebViewClientCompat;
import androidx.webkit.WebViewFeature;

import com.pichillilorenzo.flutter_inappwebview.Util;
import com.pichillilorenzo.flutter_inappwebview.credential_database.CredentialDatabase;
import com.pichillilorenzo.flutter_inappwebview.in_app_browser.InAppBrowserDelegate;
import com.pichillilorenzo.flutter_inappwebview.plugin_scripts_js.JavaScriptBridgeJS;
import com.pichillilorenzo.flutter_inappwebview.types.ClientCertChallenge;
import com.pichillilorenzo.flutter_inappwebview.types.ClientCertResponse;
import com.pichillilorenzo.flutter_inappwebview.types.CustomSchemeResponse;
import com.pichillilorenzo.flutter_inappwebview.types.HttpAuthResponse;
import com.pichillilorenzo.flutter_inappwebview.types.HttpAuthenticationChallenge;
import com.pichillilorenzo.flutter_inappwebview.types.NavigationAction;
import com.pichillilorenzo.flutter_inappwebview.types.NavigationActionPolicy;
import com.pichillilorenzo.flutter_inappwebview.types.ServerTrustAuthResponse;
import com.pichillilorenzo.flutter_inappwebview.types.ServerTrustChallenge;
import com.pichillilorenzo.flutter_inappwebview.types.URLCredential;
import com.pichillilorenzo.flutter_inappwebview.types.URLProtectionSpace;
import com.pichillilorenzo.flutter_inappwebview.types.URLRequest;
import com.pichillilorenzo.flutter_inappwebview.types.WebResourceErrorExt;
import com.pichillilorenzo.flutter_inappwebview.types.WebResourceRequestExt;
import com.pichillilorenzo.flutter_inappwebview.types.WebResourceResponseExt;
import com.pichillilorenzo.flutter_inappwebview.webview.WebViewChannelDelegate;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;

public class InAppWebViewClientCompat extends WebViewClientCompat {

  protected static final String LOG_TAG = "IAWebViewClientCompat";
  private InAppBrowserDelegate inAppBrowserDelegate;
  private static int previousAuthRequestFailureCount = 0;
  private static List<URLCredential> credentialsProposed = null;

  public InAppWebViewClientCompat(InAppBrowserDelegate inAppBrowserDelegate) {
    super();
    this.inAppBrowserDelegate = inAppBrowserDelegate;
  }

  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
  @Override
  public boolean shouldOverrideUrlLoading(@NonNull WebView view, @NonNull WebResourceRequest request) {
    InAppWebView webView = (InAppWebView) view;
    if (webView.customSettings.useShouldOverrideUrlLoading) {
      boolean isRedirect = false;
      if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_REQUEST_IS_REDIRECT)) {
        isRedirect = WebResourceRequestCompat.isRedirect(request);
      } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        isRedirect = request.isRedirect();
      }
      onShouldOverrideUrlLoading(
              webView,
              request.getUrl().toString(),
              request.getMethod(),
              request.getRequestHeaders(),
              request.isForMainFrame(),
              request.hasGesture(),
              isRedirect);
      if (webView.regexToCancelSubFramesLoadingCompiled != null) {
        if (request.isForMainFrame())
          return true;
        else {
          Matcher m = webView.regexToCancelSubFramesLoadingCompiled.matcher(request.getUrl().toString());
          return m.matches();
        }
      } else {
        // There isn't any way to load an URL for a frame that is not the main frame,
        // so if the request is not for the main frame, the navigation is allowed.
        return request.isForMainFrame();
      }
    }
    return false;
  }

  @Override
  public boolean shouldOverrideUrlLoading(WebView webView, String url) {
    InAppWebView inAppWebView = (InAppWebView) webView;
    if (inAppWebView.customSettings.useShouldOverrideUrlLoading) {
      onShouldOverrideUrlLoading(inAppWebView, url, "GET", null,true, false, false);
      return true;
    }
    return false;
  }

  private void allowShouldOverrideUrlLoading(WebView webView, String url,
                                             @Nullable Map<String, String> headers,
                                             boolean isForMainFrame) {
    if (isForMainFrame) {
      // There isn't any way to load an URL for a frame that is not the main frame,
      // so call this only on main frame.
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
        webView.loadUrl(url, headers);
      else
        webView.loadUrl(url);
    }
  }
  public void onShouldOverrideUrlLoading(final InAppWebView webView, final String url,
                                         final String method,
                                         @Nullable final Map<String, String> headers,
                                         final boolean isForMainFrame, boolean hasGesture,
                                         boolean isRedirect) {
    URLRequest request = new URLRequest(url, method, null, headers);
    NavigationAction navigationAction = new NavigationAction(
            request,
            isForMainFrame,
            hasGesture,
            isRedirect
    );

    final WebViewChannelDelegate.ShouldOverrideUrlLoadingCallback callback = new WebViewChannelDelegate.ShouldOverrideUrlLoadingCallback() {
      @Override
      public boolean nonNullSuccess(@NonNull NavigationActionPolicy result) {
        switch (result) {
          case ALLOW:
            allowShouldOverrideUrlLoading(webView, url, headers, isForMainFrame);
            break;
          case CANCEL:
          default:
            break;
        }
        return false;
      }

      @Override
      public void defaultBehaviour(@Nullable NavigationActionPolicy result) {
        allowShouldOverrideUrlLoading(webView, url, headers, isForMainFrame);
      }

      @Override
      public void error(String errorCode, @Nullable String errorMessage, @Nullable Object errorDetails) {
        Log.e(LOG_TAG, errorCode + ", " + ((errorMessage != null) ? errorMessage : ""));
        defaultBehaviour(null);
      }
    };
    
    if (webView.channelDelegate != null) {
      webView.channelDelegate.shouldOverrideUrlLoading(navigationAction, callback);
    } else {
      callback.defaultBehaviour(null);
    }
  }

  @SuppressLint("RestrictedApi")
  public void loadCustomJavaScriptOnPageStarted(WebView view) {
    InAppWebView webView = (InAppWebView) view;

    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
      String source = webView.userContentController.generateWrappedCodeForDocumentStart();
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
        webView.evaluateJavascript(source, (ValueCallback<String>) null);
      } else {
        webView.loadUrl("javascript:" + source.replaceAll("[\r\n]+", ""));
      }
    }
  }

  public void loadCustomJavaScriptOnPageFinished(WebView view) {
    InAppWebView webView = (InAppWebView) view;

    String source = webView.userContentController.generateWrappedCodeForDocumentEnd();

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      webView.evaluateJavascript(source, (ValueCallback<String>) null);
    } else {
      webView.loadUrl("javascript:" + source.replaceAll("[\r\n]+", ""));
    }
  }

  @Override
  public void onPageStarted(WebView view, String url, Bitmap favicon) {
    final InAppWebView webView = (InAppWebView) view;
    webView.isLoading = true;
    webView.disposeWebMessageChannels();
    webView.userContentController.resetContentWorlds();
    loadCustomJavaScriptOnPageStarted(webView);

    super.onPageStarted(view, url, favicon);

    if (inAppBrowserDelegate != null) {
      inAppBrowserDelegate.didStartNavigation(url);
    }

    if (webView.channelDelegate != null) {
      webView.channelDelegate.onLoadStart(url);
    }
  }
  
  public void onPageFinished(WebView view, String url) {
    final InAppWebView webView = (InAppWebView) view;
    webView.isLoading = false;
    loadCustomJavaScriptOnPageFinished(webView);
    previousAuthRequestFailureCount = 0;
    credentialsProposed = null;

    super.onPageFinished(view, url);

    if (inAppBrowserDelegate != null) {
      inAppBrowserDelegate.didFinishNavigation(url);
    }

    // WebView not storing cookies reliable to local device storage
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      CookieManager.getInstance().flush();
    } else {
      CookieSyncManager.getInstance().sync();
    }

    String js = JavaScriptBridgeJS.PLATFORM_READY_JS_SOURCE;

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      webView.evaluateJavascript(js, (ValueCallback<String>) null);
    } else {
      webView.loadUrl("javascript:" + js.replaceAll("[\r\n]+", ""));
    }

    if (webView.channelDelegate != null) {
      webView.channelDelegate.onLoadStop(url);
    }
  }

  @Override
  public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
    super.doUpdateVisitedHistory(view, url, isReload);

    // url argument sometimes doesn't contain the new changed URL, so we get it again from the webview.
    url = view.getUrl();

    if (inAppBrowserDelegate != null) {
      inAppBrowserDelegate.didUpdateVisitedHistory(url);
    }
    
    final InAppWebView webView = (InAppWebView) view;
    if (webView.channelDelegate != null) {
      webView.channelDelegate.onUpdateVisitedHistory(url, isReload);
    }
  }
  
  @RequiresApi(api = Build.VERSION_CODES.M)
  @Override
  public void onReceivedError(@NonNull WebView view,
                              @NonNull WebResourceRequest request,
                              @NonNull WebResourceErrorCompat error) {
    final InAppWebView webView = (InAppWebView) view;

    if (request.isForMainFrame()) {
      if (webView.customSettings.disableDefaultErrorPage) {
        webView.stopLoading();
        webView.loadUrl("about:blank");
      }

      webView.isLoading = false;
      previousAuthRequestFailureCount = 0;
      credentialsProposed = null;

      if (inAppBrowserDelegate != null) {
        int type = -1;
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_CODE)) {
          type = error.getErrorCode();
        }
        String description = "";
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION)) {
          description = error.getDescription().toString();
        }
        inAppBrowserDelegate.didFailNavigation(request.getUrl().toString(), type, description);
      }
    }

    if (webView.channelDelegate != null) {
      webView.channelDelegate.onReceivedError(
              WebResourceRequestExt.fromWebResourceRequest(request),
              WebResourceErrorExt.fromWebResourceError(error));
    }
  }

  @SuppressLint("RestrictedApi")
  @Override
  public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
    final InAppWebView webView = (InAppWebView) view;

    if (!WebViewFeature.isFeatureSupported(WebViewFeature.SUPPRESS_ERROR_PAGE) &&
            webView.customSettings.disableDefaultErrorPage) {
      webView.stopLoading();
      webView.loadUrl("about:blank");
    }

    webView.isLoading = false;
    previousAuthRequestFailureCount = 0;
    credentialsProposed = null;

    if (inAppBrowserDelegate != null) {
      inAppBrowserDelegate.didFailNavigation(failingUrl, errorCode, description);
    }

    WebResourceRequestExt request = new WebResourceRequestExt(
            failingUrl,
            null,
            false,
            false,
            true,
            "GET");

    WebResourceErrorExt error = new WebResourceErrorExt(
            errorCode,
            description
    );

    if (webView.channelDelegate != null) {
      webView.channelDelegate.onReceivedError(
              request,
              error);
    }

    super.onReceivedError(view, errorCode, description, failingUrl);
  }

  @RequiresApi(api = Build.VERSION_CODES.M)
  @Override
  public void onReceivedHttpError(@NonNull WebView view,
                                  @NonNull WebResourceRequest request,
                                  @NonNull WebResourceResponse errorResponse) {
    super.onReceivedHttpError(view, request, errorResponse);

    final InAppWebView webView = (InAppWebView) view;
    if (webView.channelDelegate != null) {
      webView.channelDelegate.onReceivedHttpError(
              WebResourceRequestExt.fromWebResourceRequest(request),
              WebResourceResponseExt.fromWebResourceResponse(errorResponse));
    }
  }

  @Override
  public void onReceivedHttpAuthRequest(final WebView view, final HttpAuthHandler handler, final String host, final String realm) {
    final String url = view.getUrl();
    String protocol = "https";
    int port = 0;

    if (url != null) {
      try {
        URI uri = new URI(url);
        protocol = uri.getScheme();
        port = uri.getPort();
      } catch (URISyntaxException e) {
        e.printStackTrace();
      }
    }

    previousAuthRequestFailureCount++;

    if (credentialsProposed == null)
      credentialsProposed = CredentialDatabase.getInstance(view.getContext()).getHttpAuthCredentials(host, protocol, realm, port);

    URLCredential credentialProposed = null;
    if (credentialsProposed != null && credentialsProposed.size() > 0) {
      credentialProposed = credentialsProposed.get(0);
    }

    URLProtectionSpace protectionSpace = new URLProtectionSpace(host, protocol, realm, port, view.getCertificate(), null);
    HttpAuthenticationChallenge challenge = new HttpAuthenticationChallenge(protectionSpace, previousAuthRequestFailureCount, credentialProposed);

    final InAppWebView webView = (InAppWebView) view;
    final String finalProtocol = protocol;
    final int finalPort = port;
    final WebViewChannelDelegate.ReceivedHttpAuthRequestCallback callback = new WebViewChannelDelegate.ReceivedHttpAuthRequestCallback() {
      @Override
      public boolean nonNullSuccess(@NonNull HttpAuthResponse response) {
        Integer action = response.getAction();
        if (action != null) {
          switch (action) {
            case 1:
              String username = response.getUsername();
              String password = response.getPassword();
              boolean permanentPersistence = response.isPermanentPersistence();
              if (permanentPersistence) {
                CredentialDatabase.getInstance(view.getContext())
                        .setHttpAuthCredential(host, finalProtocol, realm, finalPort, username, password);
              }
              handler.proceed(username, password);
              break;
            case 2:
              if (credentialsProposed.size() > 0) {
                URLCredential credential = credentialsProposed.remove(0);
                handler.proceed(credential.getUsername(), credential.getPassword());
              } else {
                handler.cancel();
              }
              // used custom CredentialDatabase!
              // handler.useHttpAuthUsernamePassword();
              break;
            case 0:
            default:
              credentialsProposed = null;
              previousAuthRequestFailureCount = 0;
              handler.cancel();
          }

          return false;
        }

        return true;
      }

      @Override
      public void defaultBehaviour(@Nullable HttpAuthResponse result) {
        InAppWebViewClientCompat.super.onReceivedHttpAuthRequest(view, handler, host, realm);
      }

      @Override
      public void error(String errorCode, @Nullable String errorMessage, @Nullable Object errorDetails) {
        Log.e(LOG_TAG, errorCode + ", " + ((errorMessage != null) ? errorMessage : ""));
        defaultBehaviour(null);
      }
    };
    
    if (webView.channelDelegate != null) {
      webView.channelDelegate.onReceivedHttpAuthRequest(challenge, callback);
    } else {
      callback.defaultBehaviour(null);
    }
  }

  @Override
  public void onReceivedSslError(final WebView view, final SslErrorHandler handler, final SslError sslError) {
    final String url = sslError.getUrl();
    String host = "";
    String protocol = "https";
    int port = 0;

    try {
      URI uri = new URI(url);
      host = uri.getHost();
      protocol = uri.getScheme();
      port = uri.getPort();
    } catch (URISyntaxException e) {
      e.printStackTrace();
    }

    URLProtectionSpace protectionSpace = new URLProtectionSpace(host, protocol, null, port, sslError.getCertificate(), sslError);
    ServerTrustChallenge challenge = new ServerTrustChallenge(protectionSpace);

    final InAppWebView webView = (InAppWebView) view;
    final WebViewChannelDelegate.ReceivedServerTrustAuthRequestCallback callback = new WebViewChannelDelegate.ReceivedServerTrustAuthRequestCallback() {
      @Override
      public boolean nonNullSuccess(@NonNull ServerTrustAuthResponse response) {
        Integer action = response.getAction();
        if (action != null) {
          switch (action) {
            case 1:
              handler.proceed();
              break;
            case 0:
            default:
              handler.cancel();
          }

          return false;
        }

        return true;
      }

      @Override
      public void defaultBehaviour(@Nullable ServerTrustAuthResponse result) {
        InAppWebViewClientCompat.super.onReceivedSslError(view, handler, sslError);
      }

      @Override
      public void error(String errorCode, @Nullable String errorMessage, @Nullable Object errorDetails) {
        Log.e(LOG_TAG, errorCode + ", " + ((errorMessage != null) ? errorMessage : ""));
        defaultBehaviour(null);
      }
    };
    
    if (webView.channelDelegate != null) {
      webView.channelDelegate.onReceivedServerTrustAuthRequest(challenge, callback);
    } else {
      callback.defaultBehaviour(null);
    }
  }

  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
  @Override
  public void onReceivedClientCertRequest(final WebView view, final ClientCertRequest request) {
    final String url = view.getUrl();
    final String host = request.getHost();
    String protocol = "https";
    final int port = request.getPort();

    if (url != null) {
      try {
        URI uri = new URI(url);
        protocol = uri.getScheme();
      } catch (URISyntaxException e) {
        e.printStackTrace();
      }
    }

    URLProtectionSpace protectionSpace = new URLProtectionSpace(host, protocol, null, port, view.getCertificate(), null);
    ClientCertChallenge challenge = new ClientCertChallenge(protectionSpace, request.getPrincipals(), request.getKeyTypes());

    final InAppWebView webView = (InAppWebView) view;
    final WebViewChannelDelegate.ReceivedClientCertRequestCallback callback = new WebViewChannelDelegate.ReceivedClientCertRequestCallback() {
      @Override
      public boolean nonNullSuccess(@NonNull ClientCertResponse response) {
        Integer action = response.getAction();
        if (action != null && webView.plugin != null) {
          switch (action) {
            case 1:
              {
                String certificatePath = (String) response.getCertificatePath();
                String certificatePassword = (String) response.getCertificatePassword();
                String keyStoreType = (String) response.getKeyStoreType();
                Util.PrivateKeyAndCertificates privateKeyAndCertificates = 
                        Util.loadPrivateKeyAndCertificate(webView.plugin, certificatePath, certificatePassword, keyStoreType);
                if (privateKeyAndCertificates != null) {
                  request.proceed(privateKeyAndCertificates.privateKey, privateKeyAndCertificates.certificates);
                } else {
                  request.cancel();
                }
              }
              break;
            case 2:
              request.ignore();
              break;
            case 0:
            default:
              request.cancel();
          }

          return false;
        }

        return true;
      }

      @Override
      public void defaultBehaviour(@Nullable ClientCertResponse result) {
        InAppWebViewClientCompat.super.onReceivedClientCertRequest(view, request);
      }

      @Override
      public void error(String errorCode, @Nullable String errorMessage, @Nullable Object errorDetails) {
        Log.e(LOG_TAG, errorCode + ", " + ((errorMessage != null) ? errorMessage : ""));
        defaultBehaviour(null);
      }
    };

    if (webView.channelDelegate != null) {
      webView.channelDelegate.onReceivedClientCertRequest(challenge, callback);
    } else {
      callback.defaultBehaviour(null);
    }
  }

  @Override
  public void onScaleChanged(WebView view, float oldScale, float newScale) {
    super.onScaleChanged(view, oldScale, newScale);
    final InAppWebView webView = (InAppWebView) view;
    webView.zoomScale = newScale / Util.getPixelDensity(webView.getContext());

    if (webView.channelDelegate != null) {
      webView.channelDelegate.onZoomScaleChanged(oldScale, newScale);
    }
  }

  @RequiresApi(api = Build.VERSION_CODES.O_MR1)
  @Override
  public void onSafeBrowsingHit(@NonNull final WebView view,
                                @NonNull final WebResourceRequest request,
                                final int threatType,
                                @NonNull final SafeBrowsingResponseCompat callback) {
    final InAppWebView webView = (InAppWebView) view;
    final WebViewChannelDelegate.SafeBrowsingHitCallback resultCallback = new WebViewChannelDelegate.SafeBrowsingHitCallback() {
      @Override
      public boolean nonNullSuccess(@NonNull com.pichillilorenzo.flutter_inappwebview.types.SafeBrowsingResponse response) {
        Integer action = response.getAction();
        if (action != null) {
          boolean report = response.isReport();
          switch (action) {
            case 0:
              if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_RESPONSE_BACK_TO_SAFETY)) {
                callback.backToSafety(report);
              } else {
                return true;
              }
              break;
            case 1:
              if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_RESPONSE_PROCEED)) {
                callback.proceed(report);
              } else {
                return true;
              }
              break;
            case 2:
            default:
              if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_RESPONSE_SHOW_INTERSTITIAL)) {
                callback.showInterstitial(report);
              } else {
                return true;
              }
          }

          return false;
        }

        return true;
      }

      @Override
      public void defaultBehaviour(@Nullable com.pichillilorenzo.flutter_inappwebview.types.SafeBrowsingResponse result) {
        InAppWebViewClientCompat.super.onSafeBrowsingHit(view, request, threatType, callback);
      }

      @Override
      public void error(String errorCode, @Nullable String errorMessage, @Nullable Object errorDetails) {
        Log.e(LOG_TAG, errorCode + ", " + ((errorMessage != null) ? errorMessage : ""));
        defaultBehaviour(null);
      }
    };

    if (webView.channelDelegate != null) {
      webView.channelDelegate.onSafeBrowsingHit(request.getUrl().toString(), threatType, resultCallback);
    } else {
      resultCallback.defaultBehaviour(null);
    }
  }

  public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequestExt request) {
    final InAppWebView webView = (InAppWebView) view;

    if (webView.webViewAssetLoaderExt != null && webView.webViewAssetLoaderExt.loader != null) {
      try {
        final Uri uri = Uri.parse(request.getUrl());
        WebResourceResponse webResourceResponse = webView.webViewAssetLoaderExt.loader.shouldInterceptRequest(uri);
        if (webResourceResponse != null) {
          return webResourceResponse;
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    if (webView.customSettings.useShouldInterceptRequest) {
      WebResourceResponseExt response = null;
      if (webView.channelDelegate != null) {
        try {
          response = webView.channelDelegate.shouldInterceptRequest(request);
        } catch (InterruptedException e) {
          e.printStackTrace();
          return null;
        }
      }

      if (response != null) {
        String contentType = response.getContentType();
        String contentEncoding = response.getContentEncoding();
        byte[] data = response.getData();
        Map<String, String> responseHeaders = response.getHeaders();
        Integer statusCode = response.getStatusCode();
        String reasonPhrase = response.getReasonPhrase();

        ByteArrayInputStream inputStream = (data != null) ? new ByteArrayInputStream(data) : null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && statusCode != null && reasonPhrase != null) {
          return new WebResourceResponse(contentType, contentEncoding, statusCode, reasonPhrase, responseHeaders, inputStream);
        } else {
          return new WebResourceResponse(contentType, contentEncoding, inputStream);
        }
      }

      return null;
    }

    if (webView.customSettings.useShouldInterceptResponse && webView.channelDelegate != null) {
      try {
        final String method = request.getMethod();
        if (!Objects.equals(method, "GET")) return shouldInterceptRequest(view, request.getUrl());
        Request req = new Request.Builder()
                .method(method, null)
                .url(request.getUrl())
                .headers(Headers.of(request.getHeaders()))
                .build();
        final Response response = webView.httpClientNoFollowRedirect.newCall(req).execute();
        // https://developer.android.com/reference/android/webkit/WebResourceResponse 3xx is not supported
        if (response.isRedirect()) return null;
        if (response.body() == null) return null;

        String rawContentType = response.header("content-type") == null ? response.header("Content-Type") : response.header("content-type");
        String contentTypeString = "";
        String charsetString = StandardCharsets.UTF_8.toString();
        if (rawContentType != null) {
          String[] splitContentType = rawContentType.split(";");
          if (splitContentType.length > 0) {
            contentTypeString = splitContentType[0];
          }
          if (splitContentType.length > 1) {
            String[] splitContentEncoding = splitContentType[1].split("=");
            if (splitContentEncoding.length > 1) {
              charsetString = splitContentEncoding[1];
            }
          }
        }
        if (contentTypeString == null || contentTypeString.isEmpty()) {
          MediaType contentType = response.body().contentType();
          if (contentType != null) {
            contentTypeString = contentType.type() + "/" + contentType.subtype();
            Charset charset = contentType.charset();
            if (charset != null) {
              contentTypeString = charset.toString();
            }
          }
        }

        Map<String, String> respHeaders = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : response.headers().toMultimap().entrySet()) {
          respHeaders.put(entry.getKey(), String.join("; ", entry.getValue()));
        }
        byte[] data = new byte[0];
        if (response.body() != null) {
          data = Objects.requireNonNull(response.body()).bytes();
        }
        int statusCode = 200;
        String reasonPhrase = null;
        if (!response.isSuccessful()) {
          statusCode = response.code();
          if (!response.message().equals("")) {
            reasonPhrase = response.message();
          } else {
            reasonPhrase = "HTTP " + response.code();
          }
        }

        WebResourceResponseExt res = webView.channelDelegate.shouldInterceptResponse(new WebResourceResponseExt(
                contentTypeString,
                charsetString,
                statusCode,
                reasonPhrase,
                respHeaders,
                data
        ));
        data =  res.getData();
        statusCode = 200;
        if (res.getStatusCode() != null) {
          statusCode = res.getStatusCode();
        }

        ByteArrayInputStream inputStream = (data != null) ? new ByteArrayInputStream(data) : null;

        if (res.getHeaders() == null && res.getStatusCode() == null && res.getReasonPhrase() == null) {
          return new WebResourceResponse(res.getContentType(), res.getContentEncoding(), inputStream);
        } else {
          return new WebResourceResponse(res.getContentType(), res.getContentEncoding(), statusCode, res.getReasonPhrase(), res.getHeaders(), inputStream);
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    final String url = request.getUrl();
    String scheme = url.split(":")[0].toLowerCase();
    try {
      scheme = Uri.parse(request.getUrl()).getScheme();
    } catch (Exception ignored) {}

    if (webView.customSettings.resourceCustomSchemes != null && webView.customSettings.resourceCustomSchemes.contains(scheme)) {
      CustomSchemeResponse customSchemeResponse = null;
      if (webView.channelDelegate != null) {
        try {
          customSchemeResponse = webView.channelDelegate.onLoadResourceWithCustomScheme(request);
        } catch (InterruptedException e) {
          e.printStackTrace();
          return null;
        }
      }

      if (customSchemeResponse != null) {
        WebResourceResponse response = null;
        try {
          response = webView.contentBlockerHandler.checkUrl(webView, request, customSchemeResponse.getContentType());
        } catch (Exception e) {
          e.printStackTrace();
        }
        if (response != null)
          return response;
        return new WebResourceResponse(customSchemeResponse.getContentType(),
                customSchemeResponse.getContentType(),
                new ByteArrayInputStream(customSchemeResponse.getData()));
      }
    }

    WebResourceResponse response = null;
    if (webView.contentBlockerHandler.getRuleList().size() > 0) {
      try {
        response = webView.contentBlockerHandler.checkUrl(webView, request);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    return response;
  }

  @Override
  public WebResourceResponse shouldInterceptRequest(WebView view, final String url) {
    WebResourceRequestExt requestExt = new WebResourceRequestExt(
            url, null, false,
            false, true, "GET"
    );
    return shouldInterceptRequest(view, requestExt);
  }

  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
  @Override
  public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
    WebResourceRequestExt requestExt = WebResourceRequestExt.fromWebResourceRequest(request);
    return shouldInterceptRequest(view, requestExt);
  }

  @Override
  public void onFormResubmission(final WebView view, final Message dontResend, final Message resend) {
    final InAppWebView webView = (InAppWebView) view;
    final WebViewChannelDelegate.FormResubmissionCallback callback = new WebViewChannelDelegate.FormResubmissionCallback() {
      @Override
      public boolean nonNullSuccess(@NonNull Integer action) {
        switch (action) {
          case 0:
            resend.sendToTarget();
            break;
          case 1:
          default:
            dontResend.sendToTarget();
        }
        return false;
      }

      @Override
      public void defaultBehaviour(@Nullable Integer result) {
        InAppWebViewClientCompat.super.onFormResubmission(view, dontResend, resend);
      }

      @Override
      public void error(String errorCode, @Nullable String errorMessage, @Nullable Object errorDetails) {
        Log.e(LOG_TAG, errorCode + ", " + ((errorMessage != null) ? errorMessage : ""));
        defaultBehaviour(null);
      }
    };

    if (webView.channelDelegate != null) {
      webView.channelDelegate.onFormResubmission(webView.getUrl(), callback);
    } else {
      callback.defaultBehaviour(null);
    }
  }

  @Override
  public void onPageCommitVisible(@NonNull WebView view, @NonNull String url) {
    super.onPageCommitVisible(view, url);

    final InAppWebView webView = (InAppWebView) view;
    if (webView.channelDelegate != null) {
      webView.channelDelegate.onPageCommitVisible(url);
    }
  }

  @RequiresApi(api = Build.VERSION_CODES.O)
  @Override
  public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
    final InAppWebView webView = (InAppWebView) view;

    if (webView.customSettings.useOnRenderProcessGone && webView.channelDelegate != null) {
      boolean didCrash = detail.didCrash();
      int rendererPriorityAtExit = detail.rendererPriorityAtExit();
      webView.channelDelegate.onRenderProcessGone(didCrash, rendererPriorityAtExit);
      return true;
    }

    return super.onRenderProcessGone(view, detail);
  }

  @Override
  public void onReceivedLoginRequest(WebView view, String realm, String account, String args) {
    final InAppWebView webView = (InAppWebView) view;
    if (webView.channelDelegate != null) {
      webView.channelDelegate.onReceivedLoginRequest(realm, account, args);
    }
  }

  @Override
  public void onUnhandledKeyEvent(WebView view, KeyEvent event) {}

  public void dispose() {
    if (inAppBrowserDelegate != null) {
      inAppBrowserDelegate = null;
    }
  }
}
