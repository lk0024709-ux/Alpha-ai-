package com.alphaai.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun homeShowsComposerAndExampleCards() {
        compose.onNodeWithText("Alpha AI").assertIsDisplayed()
        compose.onNodeWithText("What should I build?").assertIsDisplayed()
        compose.onNodeWithText("Try an example").assertIsDisplayed()
    }
}
