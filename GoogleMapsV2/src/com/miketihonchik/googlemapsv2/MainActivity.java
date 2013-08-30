package com.miketihonchik.googlemapsv2;

import java.util.ArrayList;
import java.util.List;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.miketihonchik.googlemapsv2.app.AppDefines;

public class MainActivity extends Activity {
	private GoogleMap googleMap;
	private List<Marker> markerList = new ArrayList<Marker>();

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		try {
			initialize();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void initialize() {
		initializeMap();
		initializeDefaultLocation();
	}

	private void initializeMap() {
		if (googleMap == null) {
			googleMap = ((MapFragment) getFragmentManager().findFragmentById(
					R.id.map)).getMap();

			if (googleMap == null) {
				Toast.makeText(getApplicationContext(),
						"Sorry! unable to create maps", Toast.LENGTH_SHORT)
						.show();
			}
		}
	}

	private void initializeDefaultLocation() {
		googleMap.addMarker(new MarkerOptions()
				.position(AppDefines.VENTSPILS)
				.title("Ventpils")
				.snippet("Ventspils, Latvia")
				.icon(BitmapDescriptorFactory
						.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)));
		Marker marker = googleMap.addMarker(new MarkerOptions()
				.position(AppDefines.KULDIGAS23)
				.title("Kuldīgas iela 23")
				.snippet("Ventspils, Latvia")
				.icon(BitmapDescriptorFactory
						.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
		marker.showInfoWindow();
		centerZoomMap();
	}

	private void centerZoomMap() {
		CameraPosition cameraPosition = new CameraPosition.Builder()
				.target(AppDefines.KULDIGAS23) // center of the map
				.zoom(14).bearing(0) // orientation of the camera to east
				.tilt(30) // tilt of the camera
				.build();
		googleMap.animateCamera(CameraUpdateFactory
				.newCameraPosition(cameraPosition));
	}

	@Override
	protected void onResume() {
		super.onResume();
		initialize();
	}
}
