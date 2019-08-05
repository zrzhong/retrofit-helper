package com.xcheng.retrofit;

import android.arch.lifecycle.Lifecycle;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.util.Log;

import java.io.IOException;

import okhttp3.Request;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

final class RealLifeCall<T> implements LifeCall<T> {

    private final Call<T> delegate;
    private final Lifecycle.Event event;
    private final boolean checkProviderNonNull;

    /**
     * LifeCall是否被释放了
     */
    private volatile boolean disposed;

    @Nullable
    private volatile LifeCallback<T> callback;

    /**
     * The executor used for {@link Callback} methods on a {@link Call}. This may be {@code null},
     * in which case callbacks should be made synchronously on the background thread.
     */
    RealLifeCall(Call<T> delegate, Lifecycle.Event event, boolean checkProviderNonNull) {
        this.delegate = delegate;
        this.event = event;
        this.checkProviderNonNull = checkProviderNonNull;
    }

    @Override
    public void enqueue(@Nullable final LifecycleProvider provider, final LifeCallback<T> callback) {
        Utils.checkNotNull(callback, "callback==null");
        //make sure call before addToProvider
        this.callback = callback;
        addToProvider(provider);
        NetTaskExecutor.getInstance().postToMainThread(new Runnable() {
            @Override
            public void run() {
                if (!disposed) {
                    callback.onStart(RealLifeCall.this);
                }
            }
        });

        delegate.enqueue(new Callback<T>() {
            @Override
            public void onResponse(Call<T> call, final Response<T> response) {
                NetTaskExecutor.getInstance().postToMainThread(new Runnable() {
                    @Override
                    public void run() {
                        callResult(response, null);
                    }
                });
            }

            @Override
            public void onFailure(Call<T> call, final Throwable t) {
                NetTaskExecutor.getInstance().postToMainThread(new Runnable() {
                    @Override
                    public void run() {
                        callResult(null, t);
                    }
                });
            }

            @UiThread
            private void callResult(@Nullable Response<T> response, @Nullable Throwable t) {
                try {
                    if (disposed)
                        return;
                    //1、获取解析结果
                    Result<T> result;
                    if (response != null) {
                        result = callback.parseResponse(RealLifeCall.this, response);
                        Utils.checkNotNull(result, "result==null");
                    } else {
                        HttpError error = callback.parseThrowable(RealLifeCall.this, t);
                        result = Result.error(error);
                    }
                    //2、回调成功失败
                    if (result.isSuccess()) {
                        callback.onSuccess(RealLifeCall.this, result.body());
                    } else {
                        callback.onError(RealLifeCall.this, result.error());
                    }
                    callback.onCompleted(RealLifeCall.this, t);
                } finally {
                    removeFromProvider(provider);
                    RealLifeCall.this.callback = null;
                }
            }
        });
    }


    @Override
    public boolean isExecuted() {
        return delegate.isExecuted();
    }


    @Override
    public Response<T> execute(@Nullable LifecycleProvider provider) throws IOException {
        addToProvider(provider);
        try {
            return delegate.execute();
        } finally {
            removeFromProvider(provider);
        }
    }

    @Override
    public void cancel() {
        delegate.cancel();
    }

    @Override
    public boolean isCanceled() {
        return delegate.isCanceled();
    }

    @SuppressWarnings("CloneDoesntCallSuperClone") // Performing deep clone.
    @Override
    public LifeCall<T> clone() {
        return new RealLifeCall<>(delegate.clone(), event, checkProviderNonNull);
    }

    @Override
    public Request request() {
        return delegate.request();
    }

    @Override
    public void onChanged(@Nullable Lifecycle.Event event) {
        //just in case
        if (disposed)
            return;
        if (this.event == event || event == Lifecycle.Event.ON_DESTROY) {
            disposed = true;
            cancel();
            LifeCallback<T> callback = this.callback;
            //volatile 会警告NullPointException
            if (callback != null) {
                callback.onDisposed(this, event);
            }
        }
    }

    @Override
    public boolean isDisposed() {
        return disposed;
    }

    private void removeFromProvider(@Nullable final LifecycleProvider provider) {
        if (provider != null) {
            NetTaskExecutor.getInstance().executeOnMainThread(new Runnable() {
                @Override
                public void run() {
                    provider.removeObserver(RealLifeCall.this);
                }
            });
        }
    }

    private void addToProvider(@Nullable final LifecycleProvider provider) {
        if (provider != null) {
            NetTaskExecutor.getInstance().executeOnMainThread(new Runnable() {
                @Override
                public void run() {
                    provider.observe(RealLifeCall.this);
                }
            });
        } else {
            if (checkProviderNonNull) {
                throw new NullPointerException("lifecycleProvider==null");
            }
            Log.w(LifeCall.TAG, "lifecycleProvider is null, lifecycle will not provide");
        }
    }
}