package de.rwth_aachen.vizzey;

import android.app.Application;

//This extension to application is only used to store measured data in memory as this may easily exceed the amount of data allowed on the transaction stack

public class App extends Application {
    public PhyphoxExperiment experiment = null;
}
