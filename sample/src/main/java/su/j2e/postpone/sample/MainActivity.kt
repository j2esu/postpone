package su.j2e.postpone.sample

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentTransaction
import su.j2e.postpone.postponeTransition

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val f = BlankFragment()
        supportFragmentManager.beginTransaction()
            .add(R.id.container, f)
            .commit()

        Handler(Looper.getMainLooper()).postDelayed({
            val f2 = BlankFragment()
            supportFragmentManager.postponeTransition(f).beginTransaction()
                .hide(f)
                .add(R.id.container, f2)
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                .addToBackStack(null)
                .setReorderingAllowed(true)
                .commit()
        }, 2000)
    }
}
