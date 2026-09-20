using System;

namespace Aurum.Companion.Core.Game
{
    public sealed class ZoneSchedule
    {
        public bool Enabled;
        public long Start, End; // UTC seconds, zero = no boundary; end is exclusive.
        public int OffsetMinutes, Days = 127, FromMinute, ToMinute;
        // Days bit 0 = Monday. An overnight interval belongs to the day it starts.
        public bool Allows(long utc)
        {
            if (!Enabled) return true;
            if ((Start != 0 && utc < Start) || (End != 0 && utc >= End)) return false;
            var local = DateTimeOffset.FromUnixTimeSeconds(utc).ToOffset(TimeSpan.FromMinutes(OffsetMinutes));
            int minute = local.Hour * 60 + local.Minute;
            int day = ((int)local.DayOfWeek + 6) % 7;
            if (FromMinute < ToMinute && (minute < FromMinute || minute >= ToMinute)) return false;
            if (FromMinute > ToMinute)
            {
                if (minute >= ToMinute && minute < FromMinute) return false;
                if (minute < ToMinute) day = (day + 6) % 7;
            }
            return (Days & (1 << day)) != 0;
        }
    }
}
