package su.j2e.postpone

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.asFlow
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenCreated
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.math.max

interface Postponable {
    val postponement: Postponement
}

interface Postponement {
    val postponed: Boolean
    fun start()
    suspend fun await()
}

interface ControlledPostponement : Postponement {
    fun end()
}

fun Fragment.postponeTransition(postponable: Postponable, fm: FragmentManager) {
    postponeEnterTransition()
    postponable.postponement.start()
    if (isAdded) {
        forEachChildRec { it.tryPostpone() }
    } else {
        doOnAttach(fm) {
            val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentAttached(fm: FragmentManager, f: Fragment, context: Context) {
                    f.tryPostpone()
                }
            }
            childFragmentManager.registerFragmentLifecycleCallbacks(callbacks, true)
            lifecycleScope.launchWhenStarted {
                childFragmentManager.unregisterFragmentLifecycleCallbacks(callbacks)
            }
        }
    }
    lifecycleScope.launch {
        postponable.postponement.await()
        startPostponedEnterTransition()
    }
}

fun <T> FragmentManager.postponeTransition(postponableFragment: T) where T : Fragment, T : Postponable {
    postponableFragment.postponeTransition(postponableFragment, this)
}

fun Fragment.postponeUntilViewCreated(
    timeoutMs: Long = 500, extraDelay: Long = 100, postponeFunc: suspend () -> Unit = {}
): Postponement {
    val postponement = PostponementImp()
    viewLifecycleOwnerLiveData.asFlow()
        .filterNotNull()
        .onEach {
            it.whenCreated {
                postponement.endAfter(timeoutMs + extraDelay) {
                    postponeFunc()
                    delay(extraDelay)
                }
            }
        }
        .launchIn(lifecycleScope)
    return postponement
}

fun Fragment.postponeControlled(): ControlledPostponement = PostponementImp()

suspend fun LiveData<*>.awaitValue() {
    asFlow().first()
}

suspend fun FragmentManager.awaitChildren() {
    fragments.forEach {
        (it as? Postponable)?.postponement?.await()
    }
}

private class PostponementImp : ControlledPostponement {

    private val awaits = mutableSetOf<Continuation<Unit>>()

    override var postponed = false
        private set

    override fun start() {
        postponed = true
    }

    override fun end() {
        awaits.forEach { it.resume(Unit) }
        awaits.clear()
        postponed = false
    }

    override suspend fun await() {
        if (postponed) {
            suspendCancellableCoroutine<Unit> { cont ->
                awaits.add(cont)
                cont.invokeOnCancellation { awaits.remove(cont) }
            }
        }
    }

    suspend fun endAfter(timeoutMs: Long, func: suspend () -> Unit) {
        if (postponed) {
            withTimeoutOrNull(timeoutMs) { func() }
            end()
        }
    }
}

private fun Fragment.tryPostpone() = (this as? Postponable)?.postponement?.start()

private fun Fragment.forEachChildRec(action: (Fragment) -> Unit) {
    childFragmentManager.fragments.forEach {
        action(it)
        it.forEachChildRec(action)
    }
}

private fun Fragment.doOnAttach(fm: FragmentManager, action: () -> Unit) {
    fm.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentAttached(fm: FragmentManager, f: Fragment, context: Context) {
            if (f == this@doOnAttach) {
                action()
                fm.unregisterFragmentLifecycleCallbacks(this)
            }
        }
    }, false)
}
