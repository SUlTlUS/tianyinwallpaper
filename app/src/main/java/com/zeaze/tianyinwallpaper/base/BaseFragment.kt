package com.zeaze.tianyinwallpaper.base

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable

abstract class BaseFragment : Fragment() {
    protected lateinit var rootView: View
    private var compositeDisposable: CompositeDisposable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        super.onCreateView(inflater, container, savedInstanceState)
        return ComposeView(requireContext()).apply {
            setContent {
                AndroidView(factory = { context ->
                    LayoutInflater.from(context).inflate(getLayout(), container, false).also {
                        rootView = it
                    }
                })
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        init()
    }

    protected fun toast(s: String) {
        activity?.runOnUiThread {
            Toast.makeText(context, s, Toast.LENGTH_SHORT).show()
        }
    }

    protected abstract fun init()
    protected abstract fun getLayout(): Int

    protected fun addDisposable(disposable: Disposable) {
        if (compositeDisposable == null) {
            compositeDisposable = CompositeDisposable()
        }
        compositeDisposable?.add(disposable)
    }

    override fun onDestroy() {
        compositeDisposable?.dispose()
        super.onDestroy()
    }

    open fun canBack(): Boolean {
        return false
    }
}
