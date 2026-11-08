package com.example.radarhumanapplication;

import android.content.Context;
import android.view.View;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileReader;
import org.junit.Test;
import org.junit.runner.RunWith;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Opt-in UI regression; credentials come from private device files, never test source. */
@RunWith(AndroidJUnit4.class)
public class ProjectBrokerDefaultsInstrumentedTest {
    @Test
    public void projectButtonFillsAllConnectionFieldsIncludingPassword() throws Exception {
        String fixture = InstrumentationRegistry.getArguments().getString("brokerFixture");
        assumeTrue("Pass a private brokerFixture to run this test", fixture != null);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JsonObject config;
        try (FileReader reader = new FileReader(new File(context.getFilesDir(), fixture))) {
            config = JsonParser.parseReader(reader).getAsJsonObject();
        }
        String expectedPassword = config.get("password").getAsString();
        assertTrue("Private fixture must contain a password", !expectedPassword.isEmpty());

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.nav_dashboard)).perform(click());
            scenario.onActivity(activity -> ((EditText) activity.findViewById(R.id.et_password))
                    .setText("temporary-test-password"));
            scenario.onActivity(activity -> {
                View button = activity.findViewById(R.id.btn_project_broker);
                assertEquals(View.VISIBLE, button.getVisibility());
                button.performClick();
            });
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());
            scenario.onActivity(activity -> {
                assertEquals(BrokerEndpoint.DEFAULT_HOST,
                        ((EditText) activity.findViewById(R.id.et_broker_host)).getText().toString());
                assertEquals(String.valueOf(BrokerEndpoint.DEFAULT_PORT),
                        ((EditText) activity.findViewById(R.id.et_broker_port)).getText().toString());
                assertEquals(BrokerEndpoint.DEFAULT_USERNAME,
                        ((EditText) activity.findViewById(R.id.et_username)).getText().toString());
                // A boolean assertion avoids printing either credential in test failure output.
                assertTrue("Project button must fill the provisioned password",
                        expectedPassword.equals(((EditText) activity.findViewById(R.id.et_password))
                                .getText().toString()));
            });
        }
    }
}
