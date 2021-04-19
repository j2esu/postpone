package su.j2e.postpone

import android.content.Context
import android.view.ViewGroup
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenCreated
import kotlinx.coroutines.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * Indicates that component can have postponed behavior. For example, [Fragment] can be
 * postponed until view is ready for transitions (i.e. initial data is loaded).
 *
 * You can control [Postponement] manually via [ControlledPostponement] or using component-specific
 * helpers, e.g. [Fragment.postponeUntilViewCreated] or [LifecycleOwner.postponeControlled]
 */
interface Postponable {
    val postponement: Postponement
}

/**
 * Allows to send postpone [request] and work with postponement status, e.g. check [postponed]
 * or [await] when postponed stuff is ready
 */
interface Postponement {
    val postponed: Boolean
    fun request()
    suspend fun await()
}

/**
 * [Postponement] with manual control. Call [done] to notify that postponed stuff is ready. Use
 * *ControlledPostponement()* function to create instances of default implementation
 *
 * @see postponeIn
 * @see doneAfter
 */
interface ControlledPostponement : Postponement {
    fun done()
}

/**
 * Helper to call [ControlledPostponement.done] after [postponeFunc] execution with given [timeoutMs].
 * Note, that [postponeFunc] will not be executed, if postponement is not postponed.
 *
 * @see postponeIn
 */
suspend fun ControlledPostponement.doneAfter(
    timeoutMs: Long = DEFAULT_TIMEOUT, postponeFunc: suspend () -> Unit
) {
    if (postponed) {
        withTimeoutOrNull(timeoutMs) { postponeFunc() }
        done()
    }
}

/**
 * Launches new coroutine in given [scope] and calls [ControlledPostponement.doneAfter] with given [timeoutMs]
 * and [postponeFunc]
 *
 * @see awaitReady
 * @see awaitChildren
 */
fun ControlledPostponement.postponeIn(
    scope: CoroutineScope, timeoutMs: Long = DEFAULT_TIMEOUT, postponeFunc: suspend () -> Unit
) {
    scope.launch { doneAfter(timeoutMs, postponeFunc) }
}

/**
 * Postpones enter fragment transition by calling [Fragment.postponeEnterTransition] immediately and invoking
 * [Fragment.startPostponedEnterTransition] when [Postponable.postponement] is ready.
 *
 * Supports nested fragments by following logic:
 * - if current fragment is added (i.e. it's a *source* in transition), then just request postpone
 * on all children (recursive)
 * - if current fragment is not added (i.e. it's a *target* in transition), then register fragment lifecycle
 * callback and request postpone on all newly attached children (recursive) until `onStart`.
 * So be careful with [awaitChildren] in parent fragments (see docs for details)
 */
fun Fragment.postponeTransition(fm: FragmentManager, postponable: Postponable = (this as Postponable)) {
    postponeEnterTransition()
    postponable.postponement.request()
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
        (view?.parent as? ViewGroup)?.let {
            it.doOnPreDraw {
                startPostponedEnterTransition()
            }
            it.invalidate()
        } ?: startPostponedEnterTransition()
    }
}

/**
 * Wrapper to call [Fragment.postponeTransition] on each fragment in [fragments]
 *
 * @return same object for future calls
 */
fun FragmentManager.postponeTransition(vararg fragments: Fragment): FragmentManager {
    fragments.forEach {
        it.postponeTransition(this)
    }
    return this
}

/**
 * Postpones fragment until view created and optional [postponeFunc] completed.
 *
 * [extraDelay] will be performed after [postponeFunc] execution with timeout specified by [timeoutMs].
 * Extra delay will not be affected by timeout, so max possible delay is `[timeout + extra delay]`
 *
 * @see [awaitChildren]
 * @see awaitReady
 */
fun Fragment.postponeUntilViewCreated(
    timeoutMs: Long = DEFAULT_TIMEOUT, extraDelay: Long = 100, postponeFunc: suspend () -> Unit = {}
): Postponement {
    val postponement = PostponementImp()
    viewLifecycleOwnerLiveData.observe(this) { owner ->
        owner?.lifecycleScope?.launchWhenStarted {
            postponement.doneAfter(timeoutMs + extraDelay) {
                postponeFunc()
                delay(extraDelay)
            }
        }
    }
    return postponement
}

/**
 * Creates [ControlledPostponement] and launches new coroutine in given [LifecycleOwner], which ensures
 * [ControlledPostponement.done] is called after specified [timeoutMs]
 */
fun LifecycleOwner.postponeControlled(timeoutMs: Long = DEFAULT_TIMEOUT): ControlledPostponement {
    val postponement = PostponementImp()
    lifecycleScope.launch {
        delay(timeoutMs)
        postponement.done()
    }
    return postponement
}

/**
 * Creates an instance of default [ControlledPostponement] implementation. You fully responsible for calling
 * [ControlledPostponement.done] after postponed stuff ready
 *
 * @see ControlledPostponement
 */
@Suppress("FunctionName")
fun ControlledPostponement(): ControlledPostponement = PostponementImp()

/**
 * Awaits all [Postponable] children, which are currently added to this [FragmentManager]
 *
 * **NOTE:** make sure that all fragments that required for postponement are added to fragment manager
 * before calling this function! Otherwise they will not affect postponement
 *
 * For first child fragment initialization [FragmentTransaction.commitNow] can be useful. It guarantees that
 * fragment is added and created after this function returns, so it has already received proper postpone
 * status in `onAttach`. Read more about nested fragments support in [postponeTransition] docs
 *
 * Also you can await children after `onCreateView`, if they were added during `onCreate` cause they are
 * always added by view creation phase
 */
suspend fun FragmentManager.awaitChildren() {
    fragments.forEach {
        (it as? Postponable)?.postponement?.await()
    }
}

/**
 * Waits until [Fragment] `created` and postponement ready (if fragment is [Postponable])
 */
suspend fun Fragment.awaitReady() {
    whenCreated { (this@awaitReady as? Postponable)?.postponement?.await() }
}

private const val DEFAULT_TIMEOUT = 500L

private class PostponementImp : ControlledPostponement {

    private val awaits = mutableSetOf<Continuation<Unit>>()

    override var postponed = false
        private set

    override fun request() {
        postponed = true
    }

    override fun done() {
        if (postponed) {
            awaits.forEach { it.resume(Unit) }
            awaits.clear()
            postponed = false
        }
    }

    override suspend fun await() {
        if (postponed) {
            suspendCancellableCoroutine<Unit> { cont ->
                awaits.add(cont)
                cont.invokeOnCancellation { awaits.remove(cont) }
            }
        }
    }
}

private fun Any.tryPostpone() {
    (this as? Postponable)?.postponement?.request()
}

private fun Fragment.forEachChildRec(action: (Fragment) -> Unit) {
    childFragmentManager.fragments.forEach {
        action(it)
        it.forEachChildRec(action)
    }
}

private fun Fragment.doOnAttach(fm: FragmentManager, action: () -> Unit) {
    if (fm.fragments.any { it == this }) {
        action()
    } else {
        fm.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentAttached(fm: FragmentManager, f: Fragment, context: Context) {
                if (f == this@doOnAttach) {
                    action()
                    fm.unregisterFragmentLifecycleCallbacks(this)
                }
            }
        }, false)
    }
}
