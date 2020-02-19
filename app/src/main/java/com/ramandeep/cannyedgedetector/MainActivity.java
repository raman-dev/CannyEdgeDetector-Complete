package com.ramandeep.cannyedgedetector;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

public class MainActivity extends AppCompatActivity {

    private DrawerLayout drawerLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        drawerLayout = findViewById(R.id.drawer_layout);

        CameraFragment cameraFragment = new CameraFragment(drawerLayout);
        getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container,cameraFragment).commit();
    }

    @Override
    public void onBackPressed() {
        //catch back press to close drawer first if open
        //other wise app will close as normal
        if(drawerLayout.isDrawerOpen(GravityCompat.END)){
            drawerLayout.closeDrawer(GravityCompat.END);
        }else{
            super.onBackPressed();
        }

    }

    @Override
    protected void onResume() {
        super.onResume();
    }
}
