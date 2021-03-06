package com.medavox.library.mutime

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.*

/**Entrypoint for the MuTime API.
 * To get the actual time:
 *<pre>
 * {@code
 * MuTime.enableDiskCache(this);
 * //use any ntp server address here, eg "time.apple.com", "time.google.com"
 * MuTime.getTheTime("0.pool.ntp.org", "1.pool.ntp.org", "2.pool.ntp.org", "3.pool.ntp.org");
 *
 * //get the real time in unix epoch format (milliseconds since midnight on 1 january 1970)
 * try {
 *     long trueTime = MuTime.now();//throws MissingTimeDataException if we don't know the time
 * }
 * catch (Exception e) {
 *     Log.e("MuTime", "failed to get the actual time:+e.getMessage());
 * }
 * }</pre>*/
object MuTime {
    private const val SERVER_QUERY_REPEAT_COUNT = 4
    private const val TAG: String = "MuTime"


    private var persistence: DiskCache? = null
    private var rebootWatcher:RebootWatcher? = null
    private var timeData: TimeData? = null
    private var timeAcquiredListener:(()->Unit)? = null

    /**
     * Initialize MuTime.
     * **THIS METHOD BE MUST BE CALLED ON A BACKGROUND THREAD!**
     * Call this to get reliable time from one or more NTP servers.
     *
     * The NTP algorithm queries each server multiple times,
     * to compensate for anomalously high delays and get the best round-trip time.
     *
     * @param ntpHosts a list of one or more NTP server URLs, of the format 0.pool.ntp.org
     * @param listener Optional listener for interested classes to be notified when MuTime is initialised.
     * NOTE: MuTime will call this lambda multiple times: whenever it receives fresh time data.
     * Do not expect this class's method to be called only once;
     * it is the listener's responsiblity to tell if a call to it was the first.
     */
    fun initialize(vararg ntpHosts:String, listener:(()->Unit)?=null) {
        if(listener != null) timeAcquiredListener = listener
        Log.i(TAG, "Getting the time from ${ntpHosts.size} IP address(es): ${ntpHosts.contentToString()}...")

        //filter the provided strings into a list of reachable IP addresses for ntp hosts
        val para:ParallelProcess<String, Array<InetAddress>?> = ParallelProcess{url ->
            InetAddress.getAllByName(url)?.filterNotNull()?.filter{it.isReachable()}?.toTypedArray()
        }
        para.oneWorkerPerElement(ntpHosts)
        val results:List<Array<InetAddress>?> = para.collectOutputWhenFinished()
        val inets = mutableSetOf<InetAddress>()
        results.filterNotNull().forEach { it.forEach { inets.add(it) } }

        //make an NTP request to each IP address
        for (ntpHost in inets) {
            Thread(Runnable{
                //Get the best response from an IP address by sending multiple requests,
                // to reduce the effect of any anomalous latency
                val bestResponse:TimeData? = bestResponseAgainstSingleIp(SERVER_QUERY_REPEAT_COUNT, ntpHost.hostAddress)
                Log.v(TAG, "got time data \"$bestResponse\" from ${ntpHost.hostAddress}")
                if(bestResponse != null) {
                    dynamicCollater.onSntpTimeData(bestResponse)
                }
            }).start()
        }
    }

    /**Whether or not MuTime knows the actual time.
     * @return Whether or not a call to [MuTime.now()] would throw a [MissingTimeDataException].*/
    fun hasTheTime(): Boolean {
        val tymDatum:TimeData? =
                if (timeData != null) {
                    //if we have time data in memory, return true
                    timeData as TimeData
                } else if (persistence?.hasTimeData() == true) {
                    //if we have time data on-disk, load it into memory and return true
                    timeData = persistence?.getTimeData()
                    timeData as TimeData
                } else {
                    //otherwise, return false
                    null
                }
        if (tymDatum != null) {
            //if either of the above are true, validate the time data before returning true
            //Check that the data is still valid:
            //check that the difference between the the uptime and system clock timestamps
            //is the same in the TimeData and right now
            val storedClocksDiff: Long = Math.abs(tymDatum.systemClockOffset - tymDatum.uptimeOffset)
            val liveClocksDiff: Long = Math.abs(System.currentTimeMillis() - SystemClock.elapsedRealtime())

            //if there's more than a 10ms difference between the stored value and the live one,
            //of the difference between the system and uptime clock
            if (Math.abs(storedClocksDiff - liveClocksDiff) > 10/*milliseconds*/) {
                Log.e(TAG, "Time Data was found to be invalid when checked! " +
                        //"A fresh network request is required to compute the correct time. " +
                        "stored clock offset: " + tymDatum.systemClockOffset + "; stored uptime offset: " +
                        tymDatum.uptimeOffset + "; live clock: " + System.currentTimeMillis() +
                        "; live uptime: " + SystemClock.elapsedRealtime() +
                        "; Stored Clock difference: " + storedClocksDiff + "; live Clock difference: " + liveClocksDiff)
                return false
            }
        }
        return tymDatum != null
    }

    /**Get the True Time in Unix Epoch format: milliseconds since midnight on 1 January, 1970.
     * The current time in the default Timezone.
     * @return a Unix Epoch-format timestamp of the actual current time, in the default timezone.*/
    @Throws(MissingTimeDataException::class)
    fun now(): Long {
        /*3 possible states:
        1. We have fresh SNTP data from a recently-made request, store (atm) in SntpClient
        2. We have cached SNTP data from SharedPreferences
        3. We have no/invalid SNTP data,
         and won't know the correct time until we make a network request
*/
        val timeInMemory = timeData ?://try to use time data in memory
            persistence?.getTimeData() ?://failing that, get it from disk

            throw MissingTimeDataException("time data is missing or invalid. " +
                    "Please make an NTP network request by calling " +
                    "MuTime.initialize(ntpServerUrl:String) to refresh the true time");
        if(timeData == null) timeData = timeInMemory//if we got time data from SP, put it in memory
        //these values should be identical, or near as dammit
        val timeFromUptime = SystemClock.elapsedRealtime() + timeInMemory.uptimeOffset
        val timeFromClock = System.currentTimeMillis() + timeInMemory.systemClockOffset

        //10ms is probably quite lenient
        if (Math.abs(timeFromClock - timeFromUptime) > 10) {
            throw MissingTimeDataException("offsets for clocks did not agree on the time - " +
                    "offset from uptime makes it " + timeFromUptime + ", " +
                    "but the offset from the clock makes it " + timeFromClock)
        }
        return timeFromClock
    }

    /**Enable the use of {@link android.content.SharedPreferences}
     * to store time data across app closes, system reboots and system clock user-meddling.
     * @param c a Context object which is needed for accessing SharedPreferences*/
    fun enableDiskCache(context:Context) {
        registerRebootWatcher(context)
        persistence = DiskCache(context.getSharedPreferences(DiskCache.SHARED_PREFS_KEY,
                Context.MODE_PRIVATE))
    }

    /**Disable storing of Time Data on-disk.*/
    fun disableDiskCache(context:Context) {
        unregisterRebootWatcher(context)
        persistence = null
    }

    /**Check whether disk caching has been enabled.
     * @return whether disk caching has been enabled*/
    fun diskCacheEnabled(): Boolean {
        return persistence != null
    }

    /**Adds a [android.content.BroadcastReceiver] which listens for the user changing the clock,
     * or the device rebooting. In these cases,
     * it repairs the partially-invalidated Time Data using the remaining intact information.
     * @param c Needed for accessing the Android BroadcastReceiver API,
     *          eg [Context.registerReceiver(BroadcastReceiver, IntentFilter)]*/
    fun registerRebootWatcher(c: Context) {
        if (rebootWatcher == null) {
            rebootWatcher = RebootWatcher()
            val intentFilter = IntentFilter()
            intentFilter.addAction(Intent.ACTION_BOOT_COMPLETED)
            intentFilter.addAction(Intent.ACTION_TIME_CHANGED)
            c.registerReceiver(rebootWatcher, intentFilter)
        } else {
            Log.w(TAG, "call to registerRebootWatcher(Context) was unnecessary: we are already registered")
        }
    }

    fun unregisterRebootWatcher(c: Context) {
        if (rebootWatcher != null) {
            c.unregisterReceiver(rebootWatcher)
            rebootWatcher = null
        } else {
            Log.w(TAG, "call to unregisterRebootWatcher(Context) was unnecessary: " +
                    "there is no TimeDataPreserver currently registered")
        }
    }

    private val dynamicCollater = object : SntpClient.SntpResponseListener {
        private val timeDatas:MutableSet<TimeData> = mutableSetOf()
        private var oldMedian:TimeData? = null

        /**Each time we receive new data,
         * find the TimeData which now has the median clock offset value,
         * and, if it's changed, send it to persistence.*/
        override fun onSntpTimeData(data:TimeData) {
            timeDatas.add(data)
            val newMedian = timeDatas.sortedBy{it.systemClockOffset}[timeDatas.size/2]
            if(newMedian != oldMedian) {
                oldMedian = newMedian
                Log.d(TAG, "new median time: $newMedian")
                Log.d(TAG, "based on ${timeDatas.size} valid readings")
                persistence?.onSntpTimeData(newMedian)
                timeAcquiredListener?.invoke()
                timeData = newMedian
            }
        }
    }

    /**
     * Takes a single NTP host (as a String),
     * performs an SNTP request on it [repeatCount] number of times,
     * and returns the single result with the lowest round-trip delay.
     *
     * Returns null if none of the requests to the IP 1) return a successful response,
     * or 2) meet the minimum NTP requirements (root delay, root dispersion, round-trip delay).
     */
    private fun bestResponseAgainstSingleIp(repeatCount:Int, ntpHost:String):TimeData?  {
        val para = ParallelProcess<String, TimeData?>{
            try{SntpClient.requestTime(it)}catch(e:IOException){null}
        }
        para.repeatOnInput(repeatCount, ntpHost)
        val results:List<TimeData> = para.collectOutputWhenFinished().filterNotNull()
        return if(results.isEmpty()) null else filterLeastRoundTripDelay(results)
    }

    /**
     * Takes a List of NTP responses, and returns the one with the smallest round-trip delay.
     * Returns null if all the passed TimeDatas are null.
     */
    private fun filterLeastRoundTripDelay(responseTimeList:List<TimeData>):TimeData?  {
        var bestRoundTrip = Long.MAX_VALUE
        var bestTimeData:TimeData? = null
        responseTimeList.forEach {
            if(it.roundTripDelay < bestRoundTrip) {
                bestRoundTrip = it.roundTripDelay
                bestTimeData = it
            }
        }

        return bestTimeData
    }

    /**Custom implementation of isReachable which checks reachability over port 80*/
    private fun InetAddress.isReachable():Boolean {
        return try {
            val soc = Socket()
            soc.connect(InetSocketAddress(this, 80), 5_000)
            true
        } catch (ex: IOException) {
            false
        }
    }
}