/*
 * Copyright (C) 2014 University of South Florida (sjbarbeau@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onebusaway.android.map.googlemapsv2;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import org.onebusaway.android.R;
import org.onebusaway.android.app.Application;
import org.onebusaway.android.io.elements.ObaRoute;
import org.onebusaway.android.io.elements.ObaTrip;
import org.onebusaway.android.io.elements.ObaTripDetails;
import org.onebusaway.android.io.elements.ObaTripStatus;
import org.onebusaway.android.io.request.ObaTripsForRouteResponse;
import org.onebusaway.android.ui.ArrivalInfo;
import org.onebusaway.android.ui.TripDetailsActivity;
import org.onebusaway.android.util.MathUtils;
import org.onebusaway.android.util.ShowcaseViewUtils;
import org.onebusaway.android.util.UIUtils;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.os.Handler;
import android.support.v4.util.LruCache;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A map overlay that shows vehicle positions on the map
 */
public class VehicleOverlay implements GoogleMap.OnInfoWindowClickListener {

    private static final String TAG = "VehicleOverlay";

    private GoogleMap mMap;

    private MarkerData mMarkerData;

    private final Activity mActivity;

    private ObaTripsForRouteResponse mLastResponse;

    private CustomInfoWindowAdapter mCustomInfoWindowAdapter;

    private static final int NORTH = 0;

    private static final int NORTH_WEST = 1;

    private static final int WEST = 2;

    private static final int SOUTH_WEST = 3;

    private static final int SOUTH = 4;

    private static final int SOUTH_EAST = 5;

    private static final int EAST = 6;

    private static final int NORTH_EAST = 7;

    private static final int NO_DIRECTION = 8;

    private static final int NUM_DIRECTIONS = 9; // 8 directions + undirected mVehicles

    private static Bitmap[] vehicle_icons = new Bitmap[NUM_DIRECTIONS];  // Black vehicle icons

    private static LruCache<String, Bitmap> mVehicleColoredIconCache;
            // Colored versions of vehicle_icons

    /**
     * If a vehicle moves less than this distance (in meters), it will be animated, otherwise it
     * will just disappear and then re-appear
     */
    private static final double MAX_VEHICLE_ANIMATION_DISTANCE = 400;

    public VehicleOverlay(Activity activity, GoogleMap map) {
        mActivity = activity;
        mMap = map;
        loadIcons();
        mMap.setOnInfoWindowClickListener(this);
        // Set adapter for custom info window that appears when tapping on vehicle markers
        mCustomInfoWindowAdapter = new CustomInfoWindowAdapter(mActivity);
        mMap.setInfoWindowAdapter(mCustomInfoWindowAdapter);
    }

    /**
     * Updates vehicles for the provided routeIds from the status info from the given
     * ObaTripsForRouteResponse
     *
     * @param routeIds routeIds for which to add vehicle markers to the map.  If a vehicle is
     *                 running a route that is not contained in this list, the vehicle won't be
     *                 shown on the map.
     * @param response response that contains the real-time status info
     */
    public void updateVehicles(HashSet<String> routeIds, ObaTripsForRouteResponse response) {
        // Make sure that the MarkerData has been initialized
        setupMarkerData();
        // Cache the response, so when a marker is tapped we can look up route names from routeIds, etc.
        mLastResponse = response;
        // Show the markers on the map
        mMarkerData.populate(routeIds, response);
    }

    public synchronized int size() {
        if (mMarkerData != null) {
            return mMarkerData.size();
        } else {
            return 0;
        }
    }

    /**
     * Clears any vehicle markers from the map
     */
    public synchronized void clear() {
        if (mMarkerData != null) {
            mMarkerData.clear();
            mMarkerData = null;
        }
        if (mCustomInfoWindowAdapter != null) {
            mCustomInfoWindowAdapter.clear();
        }
    }

    /**
     * Cache the core black template Bitmaps used for vehicle icons
     */
    private static final void loadIcons() {
        // Initialize variables used for all marker icons
        Resources r = Application.get().getResources();

        // Black vehicle icons
        if (vehicle_icons[0] == null) {
            vehicle_icons[0] = createVehicleIcon(NORTH);
            vehicle_icons[1] = createVehicleIcon(NORTH_EAST);
            vehicle_icons[2] = createVehicleIcon(EAST);
            vehicle_icons[3] = createVehicleIcon(SOUTH_EAST);
            vehicle_icons[4] = createVehicleIcon(SOUTH);
            vehicle_icons[5] = createVehicleIcon(SOUTH_WEST);
            vehicle_icons[6] = createVehicleIcon(WEST);
            vehicle_icons[7] = createVehicleIcon(NORTH_WEST);
            vehicle_icons[8] = createVehicleIcon(NO_DIRECTION);
        }

        /**
         * Cache for colored versions of the vehicle icons.  Total possible number of entries is
         * 9 directions * 4 color types (early, ontime, delayed, scheduled) = 36.  In a test,
         * the RouteMapController used around 15 bitmaps over a 30 min period for 4 vehicles on the
         * map at 10 sec refresh rate.  This can be more depending on the route configuration (if
         * the route has lots of curves) and number of vehicles.  To conserve memory, we'll set the
         * max cache size at 15.
         */
        final int MAX_CACHE_SIZE = 15;
        if (mVehicleColoredIconCache == null) {
            mVehicleColoredIconCache = new LruCache<>(MAX_CACHE_SIZE);
        }
    }

    /**
     * Creates a vehicle icon with the given direction arrow, or without a direction arrow if
     * the direction is NO_DIRECTION.  Color is black so they can be tinted later.
     *
     * @param direction vehicle direction, obtained from status.orientation, translated to direction,
     *                  and defined in constants in this class, or NO_DIRECTION if the vehicle icon
     *                  shouldn't have a direction arrow
     * @return a vehicle icon bitmap with the arrow pointing the given direction, or with no arrow
     * if direction is NO_DIRECTION
     */
    private static Bitmap createVehicleIcon(int direction) throws NullPointerException {
        if (direction < 0 || direction >= NUM_DIRECTIONS) {
            throw new IllegalArgumentException("Invalid direction - " + direction);
        }

        Resources r = Application.get().getResources();
        Bitmap b;

        switch (direction) {
            case NORTH:
                b = BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_bus_smaller_north_inside);
                break;
            case NORTH_EAST:
                b = BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_bus_smaller_north_east_inside);
                break;
            case EAST:
                b = BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_bus_smaller_east_inside);
                break;
            case SOUTH_EAST:
                b = BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_bus_smaller_south_east_inside);
                break;
            case SOUTH:
                b = BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_bus_smaller_south_inside);
                break;
            case SOUTH_WEST:
                b = BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_bus_smaller_south_west_inside);
                break;
            case WEST:
                b = BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_bus_smaller_west_inside);
                break;
            case NORTH_WEST:
                b = BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_bus_smaller_north_west_inside);
                break;
            case NO_DIRECTION:
            default:
                b = BitmapFactory
                        .decodeResource(r, R.drawable.ic_marker_with_bus_smaller_none_inside);
        }
        return b;
    }

    /**
     * Add a Bitmap for a colored vehicle icon to the cache
     * @param key Key for the Bitmap to be added, created by createBitmapCacheKey(halfWind, colorResource)
     * @param bitmap Bitmap to be added that is a colored version of the core black vehicle icons
     */
    private void addBitmapToCache(String key, Bitmap bitmap) {
        // Only add if its not already in the cache
        if (getBitmapFromCache(key) == null) {
            mVehicleColoredIconCache.put(key, bitmap);
        }
    }

    /**
     * Get a Bitmap for a colored vehicle icon from the cache
     * @param key Key for the Bitmap, created by createBitmapCacheKey(halfWind, colorResource)
     * @return Bitmap that is a colored version of the core black vehicle icons corresponding to the given key
     */
    private Bitmap getBitmapFromCache(String key) {
        return mVehicleColoredIconCache.get(key);
    }

    /**
     * Creates a key for the vehicle colored icons cache, based on the halfWind (direction) and
     * colorResource
     * @param halfWind an index between 0 and numHalfWinds-1 that can be used to retrieve
     * the direction name for that heading (known as "boxing the compass", down to the half-wind
     * level).
     * @param colorResource the color resource ID for the schedule deviation
     * @return a String key for this direction and color vehicle bitmap icon
     */
    private String createBitmapCacheKey(int halfWind, int colorResource) {
        return String.valueOf(halfWind) + " " + String.valueOf(colorResource);
    }

    @Override
    public void onInfoWindowClick(Marker marker) {
        ObaTripStatus status = mMarkerData.getStatusFromMarker(marker);
        // Show trip details screen for this vehicle
        TripDetailsActivity.start(mActivity, status.getActiveTripId());
    }

    private void setupMarkerData() {
        if (mMarkerData == null) {
            mMarkerData = new MarkerData();
        }
    }

    /**
     * Data structures to track what vehicles are currently shown on the map
     */
    class MarkerData {

        /**
         * A cached set of vehicles that are currently shown on the map.  Since onMarkerClick()
         * provides a marker, we need a mapping of that marker to a vehicle/trip.
         * Marker that represents a vehicle is the key, and value is the status for the vehicle.
         */
        private HashMap<Marker, ObaTripStatus> mVehicles;

        /**
         * A cached set of vehicle markers currently shown on the map.  This is needed to
         * add/remove markers from the map.  activeTripId is the key - we can't use vehicleId
         * because we want to show an interpolated position (based on schedule data) for trips
         * without real-time data, and those statuses do not have vehicleIds associated with them,
         * but do have activeTripIds.
         */
        private HashMap<String, Marker> mVehicleMarkers;

        private static final int INITIAL_HASHMAP_SIZE = 5;

        MarkerData() {
            mVehicles = new HashMap<>(INITIAL_HASHMAP_SIZE);
            mVehicleMarkers = new HashMap<>(INITIAL_HASHMAP_SIZE);
        }

        /**
         * Updates markers for the provided routeIds from the status info from the given
         * ObaTripsForRouteResponse
         *
         * @param routeIds markers representing real-time positions for the provided routeIds will
         *                 be
         *                 added to the map.  The response may contain status info for other routes
         *                 as well - we'll only show markers for the routeIds in this HashSet.
         * @param response response that contains the real-time status info
         */
        synchronized void populate(HashSet<String> routeIds, ObaTripsForRouteResponse response) {
            int added = 0;
            int updated = 0;
            ObaTripDetails[] trips = response.getTrips();

            // Keep track of the activeTripIds that should be shown on the map, so we don't need
            // to iterate again later for this same info
            HashSet<String> activeTripIds = new HashSet<>();

            // Add or move markers for vehicles included in response
            for (ObaTripDetails trip : trips) {
                ObaTripStatus status = trip.getStatus();
                if (status != null) {
                    // Check if this vehicle is running a route we're interested in
                    String activeRoute = response.getTrip(status.getActiveTripId()).getRouteId();
                    if (routeIds.contains(activeRoute)) {
                        Location l = status.getLastKnownLocation();
                        boolean isRealtime = true;

                        if (l == null) {
                            // Use a potentially extrapolated position instead of real last known location
                            l = status.getPosition();
                            isRealtime = false;
                        }
                        if (!status.isPredicted()) {
                            isRealtime = false;
                        }

                        Marker m = mVehicleMarkers.get(status.getActiveTripId());

                        if (m == null) {
                            // New activeTripId
                            addMarkerToMap(l, isRealtime, status, response);
                            added++;
                        } else {
                            updateMarker(m, l, isRealtime, status, response);
                            updated++;
                        }
                        activeTripIds.add(status.getActiveTripId());
                    }
                }
            }
            // Remove markers for any previously added tripIds that aren't in the current response
            int removed = removeInactiveMarkers(activeTripIds);

            Log.d(TAG,
                    "Added " + added + ", updated " + updated + ", removed " + removed
                            + ", total vehicle markers = "
                            + mVehicleMarkers.size());
            Log.d(TAG, "Vehicle LRU cache size=" + mVehicleColoredIconCache.size() + ", hits="
                    + mVehicleColoredIconCache.hitCount() + ", misses=" + mVehicleColoredIconCache
                    .missCount());
        }

        /**
         * Places a marker on the map for this vehicle, and adds it to our marker HashMap
         *
         * @param l Location to add the marker at
         * @param isRealtime true if the marker shown indicate real-time info, false if it should indicate schedule
         * @param status   the vehicles status to add to the map
         * @param response the response which contained the provided status
         */
        private void addMarkerToMap(Location l, boolean isRealtime, ObaTripStatus status,
                ObaTripsForRouteResponse response) {

            Marker m = mMap.addMarker(new MarkerOptions()
                            .position(MapHelpV2.makeLatLng(l))
                            .title(status.getVehicleId())
                            .icon(getVehicleIcon(isRealtime, status, response))
            );
            mVehicleMarkers.put(status.getActiveTripId(), m);
            mVehicles.put(m, status);
        }

        /**
         * Update an existing marker on the map with the current vehicle status
         *
         * @param m          Marker to update
         * @param l          Location to add the marker at
         * @param isRealtime true if the marker shown indicate real-time info, false if it should
         *                   indicate schedule
         * @param status     real-time status of the vehicle
         * @param response   response containing the provided status
         */
        private void updateMarker(Marker m, Location l, boolean isRealtime, ObaTripStatus status,
                ObaTripsForRouteResponse response) {
            boolean showInfo = m.isInfoWindowShown();
            m.setIcon(getVehicleIcon(isRealtime, status, response));
            // Update Hashmap with newest status - needed to show info when tapping on marker
            mVehicles.put(m, status);
            // Update vehicle position
            Location markerLoc = MapHelpV2.makeLocation(m.getPosition());
            // If its a small distance, animate the movement
            if (l.distanceTo(markerLoc) < MAX_VEHICLE_ANIMATION_DISTANCE) {
                AnimationUtil.animateMarkerTo(m, MapHelpV2.makeLatLng(l));
            } else {
                // Just snap the marker to the new location - large animations look weird
                m.setPosition(MapHelpV2.makeLatLng(l));
            }
            // If the info window was shown, make sure its open (changing the icon could have closed it)
            if (showInfo) {
                m.showInfoWindow();
            }
        }

        /**
         * Removes any markers that don't currently represent active vehicles running a route
         *
         * @param activeTripIds a set of active tripIds that are currently running the routes.  Any
         *                         markers for tripIds that aren't in this set will be removed
         *                         from the map.
         * @return the number of removed markers
         */
        private int removeInactiveMarkers(HashSet<String> activeTripIds) {
            int removed = 0;
            // Loop using an Iterator, since per Oracle Iterator.remove() is the only safe way
            // to remove an item from a Collection during iteration:
            // http://docs.oracle.com/javase/tutorial/collections/interfaces/collection.html
            try {
                Iterator<Map.Entry<String, Marker>> iterator = mVehicleMarkers.entrySet()
                        .iterator();
                while (iterator.hasNext()) {
                    Map.Entry<String, Marker> entry = iterator.next();
                    String tripId = entry.getKey();
                    Marker m = entry.getValue();
                    if (!activeTripIds.contains(tripId)) {
                        // Remove the marker from map and data structures
                        entry.getValue().remove();
                        mVehicles.remove(m);
                        iterator.remove();
                        removed++;
                    }
                }
            } catch (UnsupportedOperationException e) {
                Log.w(TAG, "Problem removing vehicle from HashMap using iterator: " + e);
                //The platform apparently didn't like the "efficient" way to do this, so we'll just
                //loop through a copy and remove what we don't want from the original
                HashMap<String, Marker> copy = new HashMap<>(mVehicleMarkers);
                for (Map.Entry<String, Marker> entry : copy.entrySet()) {
                    String tripId = entry.getKey();
                    Marker m = entry.getValue();
                    if (!activeTripIds.contains(tripId)) {
                        // Remove the marker from map and data structures
                        entry.getValue().remove();
                        mVehicles.remove(m);
                        mVehicleMarkers.remove(tripId);
                        removed++;
                    }
                }
            }
            return removed;
        }

        /**
         * Returns an icon for the vehicle that should be shown on the map
         *
         * @param isRealtime true if the marker shown indicate real-time info, false if it should
         *                   indicate schedule
         * @param status     the vehicles status to add to the map
         * @param response   the response which contained the provided status
         * @return an icon for the vehicle that should be shown on the map
         */
        private BitmapDescriptor getVehicleIcon(boolean isRealtime, ObaTripStatus status,
                ObaTripsForRouteResponse response) {
            int colorResource;
            Resources r = Application.get().getResources();

            if (isRealtime) {
                long deviationMin = TimeUnit.SECONDS.toMinutes(status.getScheduleDeviation());
                colorResource = ArrivalInfo.computeColorFromDeviation(deviationMin);
            } else {
                colorResource = R.color.stop_info_scheduled_time;
            }
            int color = r.getColor(colorResource);
            double direction = MathUtils.toDirection(status.getOrientation());
            int halfWind = MathUtils.getHalfWindIndex((float) direction, NUM_DIRECTIONS - 1);
            //Log.d(TAG, "VehicleId=" + status.getVehicleId() + ", orientation= " + status.getOrientation() + ", direction=" + direction + ", halfWind= " + halfWind + ", deviation=" + status.getScheduleDeviation());

            String key = createBitmapCacheKey(halfWind, colorResource);
            Bitmap b = getBitmapFromCache(key);
            if (b == null) {
                // Cache miss - create Bitmap and add to cache
                b = UIUtils.colorBitmap(vehicle_icons[halfWind], color);
                addBitmapToCache(key, b);
            }
            return BitmapDescriptorFactory.fromBitmap(b);
        }

        synchronized ObaTripStatus getStatusFromMarker(Marker marker) {
            return mVehicles.get(marker);
        }

        private void removeMarkersFromMap() {
            for (Map.Entry<String, Marker> entry : mVehicleMarkers.entrySet()) {
                entry.getValue().remove();
            }
        }

        /**
         * Clears any stop markers from the map
         */
        synchronized void clear() {
            if (mVehicleMarkers != null) {
                // Clear all markers from the map
                removeMarkersFromMap();

                // Clear the data structures
                mVehicleMarkers.clear();
                mVehicleMarkers = null;
            }
            if (mVehicles != null) {
                mVehicles.clear();
                mVehicles = null;
            }
        }

        synchronized int size() {
            return mVehicleMarkers.size();
        }
    }

    /**
     * Returns true if there is real-time location information for the given status, false if there
     * is not
     *
     * @param status The trip status information that includes location information
     * @return true if there is real-time location information for the given status, false if there
     * is not
     */
    protected static boolean isLocationRealtime(ObaTripStatus status) {
        boolean isRealtime = true;
        Location l = status.getLastKnownLocation();
        if (l == null) {
            isRealtime = false;
        }
        if (!status.isPredicted()) {
            isRealtime = false;
        }
        return isRealtime;
    }

    /**
     * Adapter to show custom info windows when tapping on vehicle markers
     */
    class CustomInfoWindowAdapter implements GoogleMap.InfoWindowAdapter {

        private LayoutInflater mInflater;

        private Context mContext;

        private Marker mCurrentFocusVehicleMarker;

        public CustomInfoWindowAdapter(Context context) {
            this.mInflater = LayoutInflater.from(context);
            this.mContext = context;
        }

        @Override
        public View getInfoWindow(Marker marker) {
            return null;
        }

        @Override
        public View getInfoContents(Marker marker) {
            if (mMarkerData == null) {
                // Markers haven't been initialized yet - use default rendering
                return null;
            }
            ObaTripStatus status = mMarkerData.getStatusFromMarker(marker);
            if (status == null) {
                // Marker that the user tapped on wasn't a vehicle - use default rendering
                mCurrentFocusVehicleMarker = null;
                return null;
            }
            mCurrentFocusVehicleMarker = marker;
            View view = mInflater.inflate(R.layout.vehicle_info_window, null);
            Resources r = mContext.getResources();
            TextView routeView = (TextView) view.findViewById(R.id.route_and_destination);
            TextView statusView = (TextView) view.findViewById(R.id.status);
            TextView lastUpdatedView = (TextView) view.findViewById(R.id.last_updated);
            ImageView moreView = (ImageView) view.findViewById(R.id.trip_more_info);
            moreView.setColorFilter(r.getColor(R.color.switch_thumb_normal_material_dark));

            // Get route/trip details
            ObaTrip trip = mLastResponse.getTrip(status.getActiveTripId());
            ObaRoute route = mLastResponse.getRoute(trip.getRouteId());

            routeView.setText(UIUtils.getRouteDisplayName(route) + " " +
                    mContext.getString(R.string.trip_info_separator) + " " + trip.getHeadsign());

            boolean isRealtime = isLocationRealtime(status);

            statusView.setBackgroundResource(R.drawable.round_corners_style_b_status);
            GradientDrawable d = (GradientDrawable) statusView.getBackground();

            // Set padding on status view
            int pSides = UIUtils.dpToPixels(mContext, 5);
            int pTopBottom = UIUtils.dpToPixels(mContext, 2);

            int statusColor;

            if (isRealtime) {
                long deviationMin = TimeUnit.SECONDS.toMinutes(status.getScheduleDeviation());
                String statusString = ArrivalInfo.computeArrivalLabelFromDelay(r, deviationMin);
                statusView.setText(statusString);
                statusColor = ArrivalInfo.computeColorFromDeviation(deviationMin);
                d.setColor(r.getColor(statusColor));
                statusView.setPadding(pSides, pTopBottom, pSides, pTopBottom);
            } else {
                // Scheduled info
                statusView.setText(r.getString(R.string.stop_info_scheduled));
                statusColor = R.color.stop_info_scheduled_time;
                d.setColor(r.getColor(statusColor));
                lastUpdatedView.setText(r.getString(R.string.vehicle_last_updated_scheduled));
                statusView.setPadding(pSides, pTopBottom, pSides, pTopBottom);
                return view;
            }

            // Update last updated time (only shown for real-time info)
            long now = System.currentTimeMillis();
            long lastUpdateTime;
            // Use the last updated time for the position itself, if its available
            if (status.getLastLocationUpdateTime() != 0) {
                lastUpdateTime = status.getLastLocationUpdateTime();
            } else {
                // Use the status timestamp for last updated time
                lastUpdateTime = status.getLastUpdateTime();
            }
            long elapsedSec = TimeUnit.MILLISECONDS.toSeconds(now - lastUpdateTime);
            long elapsedMin = TimeUnit.SECONDS.toMinutes(elapsedSec);
            long secMod60 = elapsedSec % 60;

            String lastUpdated;
            if (elapsedSec < 60) {
                lastUpdated = r.getString(R.string.vehicle_last_updated_sec,
                        elapsedSec);
            } else {
                lastUpdated = r.getString(R.string.vehicle_last_updated_min_and_sec,
                        elapsedMin, secMod60);
            }
            lastUpdatedView.setText(lastUpdated);

            if (mMarkerRefreshHandler != null) {
                mMarkerRefreshHandler.removeCallbacks(mMarkerRefresh);
                mMarkerRefreshHandler.postDelayed(mMarkerRefresh, MARKER_REFRESH_PERIOD);
            }

            ShowcaseViewUtils.showTutorial(ShowcaseViewUtils.TUTORIAL_VEHICLE_INFO_WINDOW,
                    (AppCompatActivity) mActivity, null);

            return view;
        }

        private final long MARKER_REFRESH_PERIOD = TimeUnit.SECONDS.toMillis(1);

        private final Handler mMarkerRefreshHandler = new Handler();

        private final Runnable mMarkerRefresh = new Runnable() {
            public void run() {
                if (mCurrentFocusVehicleMarker != null &&
                        mCurrentFocusVehicleMarker.isInfoWindowShown()) {
                    // Force an update of the marker balloon, so "last updated" time ticks up
                    mCurrentFocusVehicleMarker.showInfoWindow();
                }
            }
        };

        /**
         * Cancels any pending updates of the marker balloon contents
         */
        public void clear() {
            if (mMarkerRefreshHandler != null) {
                mMarkerRefreshHandler.removeCallbacks(mMarkerRefresh);
            }
        }
    }
}
