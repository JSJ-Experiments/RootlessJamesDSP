package me.timschneeberger.rootlessjamesdsp.preference

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.preference.ListPreference
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceViewHolder
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.TwoStatePreference
import androidx.preference.children
import com.google.android.material.materialswitch.MaterialSwitch
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.extensions.animatedValueAs
import timber.log.Timber


@SuppressLint("PrivateResource")
class SwitchPreferenceGroup(context: Context, attrs: AttributeSet) : PreferenceGroup(
    context, attrs, androidx.preference.R.attr.preferenceStyle,
    androidx.preference.R.style.Preference_SwitchPreferenceCompat_Material
) {
    private var childrenVisible = false
    private var switch: MaterialSwitch? = null
    private var resetButton: View? = null
    private var itemView: View? = null
    private var bgAnimation: ValueAnimator? = null
    private var isIconVisible: Boolean = false
    private var state = false

    init {
        layoutResource = R.layout.preference_switchgroup
        widgetLayoutResource = R.layout.preference_materialswitch
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        setValueInternal(getPersistedBoolean((defaultValue as? Boolean) ?: false), true)
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any = a.getBoolean(index, false)

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        itemView = holder.itemView
        itemView?.background = ContextCompat.getDrawable(context, R.drawable.shape_rounded_highlight)
        itemView?.background?.alpha = 0

        bgAnimation = ValueAnimator.ofInt(TRANSITION_MIN, TRANSITION_MAX).apply {
            duration = 200 // milliseconds
            addUpdateListener { animator ->
                itemView?.background?.alpha = animator.animatedValueAs<Int>() ?: 0
            }
        }

        setChildrenVisibility(state)
        animateHeaderState(state)
        setIsIconVisible(isIconVisible)

        switch = holder.findViewById(R.id.switchWidget) as? MaterialSwitch
        switch?.apply {
            // Apply initial state
            isChecked = state
            isVisible = isSelectable

            setOnCheckedChangeListener { _, isChecked ->
                setValueInternal(isChecked, false)
            }
        }
        resetButton = holder.findViewById(R.id.resetWidget)
        resetButton?.apply {
            isVisible = hasAnyChildDefaults()
            setOnClickListener {
                resetChildrenToDefaults()
            }
        }

        holder.itemView.apply {
            setOnClickListener {
                switch?.toggle()
            }
        }
    }

    override fun onPrepareAddPreference(preference: Preference): Boolean {
        preference.isVisible = childrenVisible
        return super.onPrepareAddPreference(preference)
    }

    fun setIsIconVisible(value: Boolean) {
        isIconVisible = value
        itemView?.findViewById<View>(R.id.icon_frame)?.isVisible = value
    }

    fun setValue(value: Boolean) {
        setValueInternal(value, true)
    }

    private fun setValueInternal(value: Boolean, notifyChanged: Boolean) {
        setChildrenVisibility(value)
        if (state != value) {
            animateHeaderState(value)

            state = value
            persistBoolean(state)
            if (notifyChanged) {
                notifyChanged()
            }
        }
    }

    private fun animateHeaderState(selected: Boolean) {
        val current = bgAnimation?.animatedValueAs<Int>() ?: 0
        if(selected && current < TRANSITION_MAX)
            bgAnimation?.start()
        else if(!selected && current > TRANSITION_MIN)
            bgAnimation?.reverse()
    }

    private fun setChildrenVisibility(visible: Boolean) {
        children.forEach { it.isVisible = visible }
    }

    private fun resetChildrenToDefaults() {
        flattenChildren(this).forEach { child ->
            if (!resetPreferenceToDefault(child) && !child.key.isNullOrEmpty()) {
                preferenceManager.sharedPreferences?.edit()
                    ?.remove(child.key)
                    ?.apply()
                forceNotifyChanged(child)
            }
        }
    }

    private fun flattenChildren(group: PreferenceGroup): List<Preference> {
        val nodes = mutableListOf<Preference>()
        group.children.forEach { child ->
            nodes += child
            if (child is PreferenceGroup) {
                nodes += flattenChildren(child)
            }
        }
        return nodes
    }

    private fun hasAnyChildDefaults(): Boolean {
        return flattenChildren(this).any { resolveDefaultValue(it) != null }
    }

    private fun resetPreferenceToDefault(preference: Preference): Boolean {
        val defaultValue = resolveDefaultValue(preference) ?: return false
        return when (preference) {
            is MaterialSeekbarPreference -> {
                parseFloat(defaultValue)?.let {
                    preference.setValue(it)
                    true
                } ?: false
            }
            is SeekBarPreference -> {
                parseInt(defaultValue)?.let {
                    preference.value = it
                    true
                } ?: false
            }
            is ListPreference -> {
                val value = defaultValue.toString()
                preference.value = value
                true
            }
            is EditTextPreference -> {
                preference.text = defaultValue.toString()
                true
            }
            is SwitchPreferenceCompat -> {
                parseBoolean(defaultValue)?.let {
                    preference.isChecked = it
                    true
                } ?: false
            }
            is SwitchPreferenceGroup -> {
                parseBoolean(defaultValue)?.let {
                    preference.setValue(it)
                    true
                } ?: false
            }
            else -> false
        }
    }

    private fun resolveDefaultValue(preference: Preference): Any? {
        return try {
            // Reflection note: this accesses Preference::class.java.getDeclaredField("mDefaultValue")
            // (verified against androidx.preference 1.2.1). This is brittle across library upgrades
            // and may break if internals change; failures are handled by returning null so reset is disabled,
            // with details logged in the catch block via Timber.
            // TODO: replace this with a public API or add compatibility handling if one becomes available.
            val field = Preference::class.java.getDeclaredField("mDefaultValue")
            field.isAccessible = true
            field.get(preference)
        } catch (ex: Exception) {
            Timber.d(ex, "Failed to read default value for preference '${preference.key}'")
            null
        }
    }

    private fun forceNotifyChanged(preference: Preference) {
        try {
            val method = Preference::class.java.getDeclaredMethod("notifyChanged")
            method.isAccessible = true
            method.invoke(preference)
        } catch (ex: Exception) {
            Timber.d(ex, "Failed to notify preference change for '${preference.key}'")
        }
    }

    private fun parseFloat(value: Any): Float? {
        return when (value) {
            is Float -> value
            is Double -> value.toFloat()
            is Int -> value.toFloat()
            is Long -> value.toFloat()
            is String -> value.toFloatOrNull()
            else -> null
        }
    }

    private fun parseInt(value: Any): Int? {
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Float -> value.toInt()
            is Double -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    private fun parseBoolean(value: Any): Boolean? {
        return when (value) {
            is Boolean -> value
            is String -> value.toBooleanStrictOrNull()
            is Number -> value.toInt() != 0
            else -> null
        }
    }

    companion object {
        private const val TRANSITION_MIN = 0
        private const val TRANSITION_MAX = 255
    }
}
