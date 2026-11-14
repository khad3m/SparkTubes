package com.sparktube.app.ui

/** Implemented by tab fragments so tapping the active tab again scrolls them to the top (iOS behavior). */
interface ScrollsToTop {
    fun scrollToTop()
}
