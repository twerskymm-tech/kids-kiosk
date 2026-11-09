package com.kidskiosk.phone;

import com.kosherjava.zmanim.ComplexZmanimCalendar;
import com.kosherjava.zmanim.hebrewcalendar.JewishCalendar;
import com.kosherjava.zmanim.util.GeoLocation;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/**
 * Shabbos and Yom Tov times, worked out on the phone (no internet) with the KosherJava zmanim library.
 * The lock runs from candle lighting before Shabbos / Yom Tov until havdalah
 * (sunset + a set number of minutes) at the end, including back-to-back days (Yom Tov next to Shabbos).
 * Plain Java with no Android parts, so it can be checked on a computer.
 */
public final class Shabbos {
    private Shabbos() {}

    /** One Shabbos / Yom Tov stretch: from candle lighting to havdalah. */
    public static final class Window {
        public final Date start;
        public final Date end;
        public final boolean yomTov;
        Window(Date start, Date end, boolean yomTov) { this.start = start; this.end = end; this.yomTov = yomTov; }
    }

    public static final class Place {
        final double lat, lon; final TimeZone tz; final boolean israel; final int candleMin, havdalahMin;
        public Place(double lat, double lon, TimeZone tz, boolean israel, int candleMin, int havdalahMin) {
            this.lat = lat; this.lon = lon; this.tz = tz; this.israel = israel; this.candleMin = candleMin; this.havdalahMin = havdalahMin;
        }
    }

    private static JewishCalendar jc(LocalDate d, Place p) {
        JewishCalendar j = new JewishCalendar(d);
        j.setInIsrael(p.israel);
        return j;
    }

    private static boolean assur(LocalDate d, Place p) { return jc(d, p).isAssurBemelacha(); }

    private static ComplexZmanimCalendar zc(LocalDate d, Place p) {
        ComplexZmanimCalendar z = new ComplexZmanimCalendar(new GeoLocation("home", p.lat, p.lon, p.tz));
        Calendar c = Calendar.getInstance(p.tz);
        c.clear();
        c.set(d.getYear(), d.getMonthValue() - 1, d.getDayOfMonth(), 12, 0, 0);
        z.setCalendar(c);
        z.setCandleLightingOffset(p.candleMin);
        return z;
    }

    private static Date candle(LocalDate d, Place p) { return zc(d, p).getCandleLighting(); }

    private static Date havdalah(LocalDate d, Place p) {
        Date sunset = zc(d, p).getSeaLevelSunset();
        return sunset == null ? null : new Date(sunset.getTime() + p.havdalahMin * 60_000L);
    }

    /** The stretch we are in now, or the next one coming (looks up to 60 days ahead). Null if it can't be worked out. */
    public static Window currentOrNext(Date now, Place p) {
        LocalDate today = now.toInstant().atZone(ZoneId.of(p.tz.getID())).toLocalDate();
        for (int k = -3; k <= 60; k++) {
            LocalDate eve = today.plusDays(k);
            if (assur(eve, p) || !assur(eve.plusDays(1), p)) continue;
            // eve = the weekday before; the stretch runs while the days after it are assur
            LocalDate last = eve.plusDays(1);
            boolean yt = false;
            while (true) {
                if (jc(last, p).isYomTovAssurBemelacha()) yt = true;
                if (!assur(last.plusDays(1), p)) break;
                last = last.plusDays(1);
            }
            Date start = candle(eve, p);
            Date end = havdalah(last, p);
            if (start == null || end == null) return null;
            if (end.after(now)) return new Window(start, end, yt);
        }
        return null;
    }

    /** The stretch if it is going on right now, otherwise null. */
    public static Window now(Date now, Place p) {
        Window w = currentOrNext(now, p);
        return (w != null && !w.start.after(now)) ? w : null;
    }
}
