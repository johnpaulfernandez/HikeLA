package com.projects.hikela;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.SearchView;
import android.widget.Toast;

import com.esri.arcgisruntime.concurrent.ListenableFuture;
import com.esri.arcgisruntime.loadable.LoadStatus;
import com.esri.arcgisruntime.mapping.ArcGISMap;
import com.esri.arcgisruntime.mapping.Basemap;
import com.esri.arcgisruntime.mapping.MobileMapPackage;
import com.esri.arcgisruntime.mapping.view.Graphic;
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay;
import com.esri.arcgisruntime.mapping.view.MapView;
import com.esri.arcgisruntime.symbology.SimpleMarkerSymbol;
import com.esri.arcgisruntime.symbology.TextSymbol;
import com.esri.arcgisruntime.tasks.geocode.GeocodeParameters;
import com.esri.arcgisruntime.tasks.geocode.GeocodeResult;
import com.esri.arcgisruntime.tasks.geocode.LocatorTask;
import com.esri.arcgisruntime.util.ListenableList;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity {

    private MapView mMapView;
    private SearchView mSearchView = null;
    private GraphicsOverlay mGraphicsOverlay;
    private LocatorTask mLocatorTask = null;
    private GeocodeParameters mGeocodeParameters = null;
    private boolean noNetworkOnCreate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mMapView = (MapView) findViewById(R.id.mapView);

        if (isNetworkAvailable()) {
            showWebMap();
            setupLocator(); // Prepare to accept user search requests
        } else {
            noNetworkOnCreate = true;
            setupMobileMap();
        }
    }

    // Create the search widget and add it to the action bar
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.options_menu, menu);
        MenuItem searchMenuItem = menu.findItem(R.id.search);
        if (searchMenuItem != null) {
            mSearchView = (SearchView) searchMenuItem.getActionView();
            if (mSearchView != null) {
                SearchManager searchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
                mSearchView.setSearchableInfo(searchManager.getSearchableInfo(getComponentName()));
                mSearchView.setIconifiedByDefault(false);
            }
        }
        return true;
    }

    // In response to a user input, the search widget sends an intent to the main activity
    // Accept this intent, verify it is a search action, and perform the search
    // launchMode in the manifest file is singleTop
    // If there already is an Activity instance with the same type at the top of stack in the caller task,
    // there would not be any new Activity created,
    // instead an Intent will be sent to an existing Activity instance through onNewIntent() method.
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            if (isNetworkAvailable()) {
                if (noNetworkOnCreate) {
                    Toast.makeText(this, "Please restart the app to use geocoding feature.", Toast.LENGTH_SHORT).show();
                } else {
                    // If there has been a network connection when the app is first started.
                    queryLocator(intent.getStringExtra(SearchManager.QUERY));
                }
            } else {
                Toast.makeText(this, "Geocoding is not available.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // The MapView needs to know when your app goes to the background or is restored from background in order to properly manage the view.
    @Override
    protected void onPause() {
        mMapView.pause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mMapView.resume();
    }

    private void setupMap() {
        if (mMapView != null) {
            Basemap.Type basemapType = Basemap.Type.STREETS_VECTOR;
            double latitude = 34.05293;
            double longitude = -118.24368;
            int levelOfDetail = 11;

            ArcGISMap map = new ArcGISMap(basemapType, latitude, longitude, levelOfDetail);
            mMapView.setMap(map);
        }
    }

    // Initialize the LocatorTask and GeocodeParameters
    private void setupLocator() {

        String locatorLocation;

        // Use Esri's World Geocode locator service
        locatorLocation = "https://geocode.arcgis.com/arcgis/rest/services/World/GeocodeServer";

        // Initializing the locator task is an asynchronous process because we do not want to block the device while contacting the service over the network.
        // Wait until the locator task is fully loaded before continuing.
        mLocatorTask = new LocatorTask(locatorLocation);
        mLocatorTask.addDoneLoadingListener(new Runnable() {
            @Override
            public void run() {
                // Once the locator task is loaded, create the geocode parameters object.
                // Request that the geocode service returns all attributes in the results and at most one result per request.
                if (mLocatorTask.getLoadStatus() == LoadStatus.LOADED) {
                    mGeocodeParameters = new GeocodeParameters();
                    mGeocodeParameters.getResultAttributeNames().add("*");
                    mGeocodeParameters.setMaxResults(1);

                    // Create a graphics layer where the search results are displayed, and then add the graphics overlay to the map view.
                    mGraphicsOverlay = new GraphicsOverlay();
                    mMapView.getGraphicsOverlays().add(mGraphicsOverlay);
                } else if (mSearchView != null) {
                    Log.d("DEBUG", "setupLocator: " + mLocatorTask.getLoadError());
                    mSearchView.setEnabled(false);
                }
            }
        });
        mLocatorTask.loadAsync();
    }

    // Perform the geocode search given a search string
    private void queryLocator(final String query) {

        // Verify a search request is provided as we do not want to attempt to search on something invalid
        if (query != null && query.length() > 0) {

            // If a current search is running then cancel it as you can only perform one search at a time
            mLocatorTask.cancelLoad();

            // The search request is sent to a server over the network.
            // This requires an asynchronous task because we do not want to block the device while we wait for the server reply.
            final ListenableFuture<List<GeocodeResult>> geocodeFuture = mLocatorTask.geocodeAsync(query, mGeocodeParameters);
            geocodeFuture.addDoneListener(new Runnable() {
                @Override
                public void run() {
                    try {
                        List<GeocodeResult> geocodeResults = geocodeFuture.get();

                        // Geocoding results may return more than one possible match.
                        // Use the first match returned.
                        if (geocodeResults.size() > 0) {
                            displaySearchResult(geocodeResults.get(0));
                        } else {
                            // If there are no matches, display a message to the user
                            Toast.makeText(getApplicationContext(), getString(R.string.nothing_found) + " " + query, Toast.LENGTH_LONG).show();
                        }
                    } catch (InterruptedException | ExecutionException e) {
                        // ... determine how you want to handle an error
                    }
                    geocodeFuture.removeDoneListener(this); // Done searching, remove the listener.
                }
            });
        }
    }

    // Display the GeocodeResult on the map
    private void displaySearchResult(GeocodeResult geocodedLocation) {

        // Show a square marker and a text label drawn on a graphics layer
        String displayLabel = geocodedLocation.getLabel();
        TextSymbol textLabel = new TextSymbol(18, displayLabel, Color.rgb(192, 32, 32), TextSymbol.HorizontalAlignment.CENTER, TextSymbol.VerticalAlignment.BOTTOM);
        Graphic textGraphic = new Graphic(geocodedLocation.getDisplayLocation(), textLabel);
        Graphic mapMarker = new Graphic(geocodedLocation.getDisplayLocation(), geocodedLocation.getAttributes(),
                new SimpleMarkerSymbol(SimpleMarkerSymbol.Style.SQUARE, Color.rgb(255, 0, 0), 12.0f));

        ListenableList allGraphics = mGraphicsOverlay.getGraphics();

        // Any existing graphics from a prior search are removed before displaying the current search result
        allGraphics.clear();
        allGraphics.add(mapMarker);
        allGraphics.add(textGraphic);

        // The map view is updated to center the view on the search location.
        mMapView.setViewpointCenterAsync(geocodedLocation.getDisplayLocation());
    }

    //  Create a map object from a web map given its URL
    private void showWebMap() {
        String itemId = "5c3b90e2f3de4c8fae0b4c711104bcb1";
        String url = "https://www.arcgis.com/sharing/rest/content/items/" + itemId + "/data";
        ArcGISMap map = new ArcGISMap(url);
        mMapView.setMap(map);
    }

    private void setupMobileMap() {

        if (mMapView != null) {

            File mmpkFile = new File(getApplicationContext().getExternalFilesDir(null), "HikeLAMobileMapPackageFile.mmpk");
            final MobileMapPackage mapPackage = new MobileMapPackage(mmpkFile.getAbsolutePath());

            mapPackage.addDoneLoadingListener(new Runnable() {
                @Override
                public void run() {
                    // Verify the file loaded and there is at least one map
                    if (mapPackage.getLoadStatus() == LoadStatus.LOADED && mapPackage.getMaps().size() > 0) {
                        mMapView.setMap(mapPackage.getMaps().get(0));
                    } else {
                        // Error if the mobile map package fails to load or there are no maps included in the package
                        Log.d("DEBUG", "setupMobileMap: " + mapPackage.getLoadError());
                        setupMap();
                    }
                }
            });

            // load the mobile map package asynchronously
            mapPackage.loadAsync();
        }
    }

    private Boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager
                = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnectedOrConnecting();
    }
}
