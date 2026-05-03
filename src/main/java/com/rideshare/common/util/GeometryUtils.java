package com.rideshare.common.util;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

public final class GeometryUtils {

    private static final int SRID_WGS84 = 4326;
    private static final GeometryFactory FACTORY = new GeometryFactory(new PrecisionModel(), SRID_WGS84);

    private GeometryUtils() {}

    /**
     * Build a PostGIS-compatible Point from longitude and latitude.
     * Note: PostGIS uses (x=lng, y=lat) order.
     */
    public static Point point(double longitude, double latitude) {
        Point p = FACTORY.createPoint(new Coordinate(longitude, latitude));
        p.setSRID(SRID_WGS84);
        return p;
    }
}
