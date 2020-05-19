/*
 * Androzic - android navigation client that uses OziExplorer maps (ozf2, ozfx3).
 * Copyright (C) 2010-2012  Andrey Novikov <http://andreynovikov.info/>
 *
 * This file is part of Androzic application.
 *
 * Androzic is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * Androzic is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with Androzic.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.borkozic.util;

public class Geo
{
	public static double distance(double lat1, double lon1, double lat2, double lon2)
	{
		// WGS-84 ellipsoid
		double a = 6378137;
		double b = 6356752.314245;
		double f = 1/298.257223563;
		double L = Math.toRadians(lon2-lon1);
		double U1 = Math.atan((1-f) * Math.tan(Math.toRadians(lat1)));
		double U2 = Math.atan((1-f) * Math.tan(Math.toRadians(lat2)));
		double sinU1 = Math.sin(U1);
		double cosU1 = Math.cos(U1);
		double sinU2 = Math.sin(U2);
		double cosU2 = Math.cos(U2);
  
		double lambda = L, lambdaP, iterLimit = 100;
		double sigma, cosSqAlpha, sinSigma, cosSigma, cos2SigmaM;
		
		do
		{
			double sinLambda = Math.sin(lambda);
			double cosLambda = Math.cos(lambda);
			sinSigma = Math.sqrt((cosU2*sinLambda) * (cosU2*sinLambda) + (cosU1*sinU2-sinU1*cosU2*cosLambda) * (cosU1*sinU2-sinU1*cosU2*cosLambda));
			if (sinSigma==0) return 0;  // co-incident points
			cosSigma = sinU1*sinU2 + cosU1*cosU2*cosLambda;
			sigma = Math.atan2(sinSigma, cosSigma);
			double sinAlpha = cosU1 * cosU2 * sinLambda / sinSigma;
			cosSqAlpha = 1 - sinAlpha*sinAlpha;
			try
			{
				cos2SigmaM = cosSigma - 2*sinU1*sinU2/cosSqAlpha;
			}
			catch (ArithmeticException e)
			{
				cos2SigmaM = 0;  // equatorial line: cosSqAlpha=0
			}
			double C = f/16*cosSqAlpha*(4+f*(4-3*cosSqAlpha));
			lambdaP = lambda;
			lambda = L + (1-C) * f * sinAlpha * (sigma + C*sinSigma*(cos2SigmaM+C*cosSigma*(-1+2*cos2SigmaM*cos2SigmaM)));
		} 
		while (Math.abs(lambda-lambdaP) > 1e-12 && --iterLimit>0);

		if (iterLimit==0) return -1;  // formula failed to converge

		double uSq = cosSqAlpha * (a*a - b*b) / (b*b);
		double A = 1 + uSq/16384*(4096+uSq*(-768+uSq*(320-175*uSq)));
		double B = uSq/1024 * (256+uSq*(-128+uSq*(74-47*uSq)));
		double deltaSigma = B*sinSigma*(cos2SigmaM+B/4*(cosSigma*(-1+2*cos2SigmaM*cos2SigmaM)-B/6*cos2SigmaM*(-3+4*sinSigma*sinSigma)*(-3+4*cos2SigmaM*cos2SigmaM)));
		double s = b*A*(sigma-deltaSigma);
  
		return s;
	}
	


	/**
	 * Returns VMG (velocity made good)
	 * @param speed movement speed
	 * @param turn desired turn, in degrees
	 * @return VMG in speed units
	 */
	public static double vmg(double speed, double turn)
	{
		return speed * Math.cos(Math.toRadians(turn));
	}

	/**
	 * Returns XTK (off course) when navigating from point A to point B
	 * @param distance current distance to point B
	 * @param dtk desired track (course from A to B), in degrees
	 * @param bearing direction to B, in degrees
	 * @return XTK in distance units
	 */

}
