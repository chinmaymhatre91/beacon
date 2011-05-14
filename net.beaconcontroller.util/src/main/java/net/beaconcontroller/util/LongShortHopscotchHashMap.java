package net.beaconcontroller.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * Internal array layout [key 8 bytes][value 2 bytes][blank 2 bytes][hop_info 4 bytes]
 * @author David Erickson (daviderickson@cs.stanford.edu)
 */
public class LongShortHopscotchHashMap {
    protected static Logger log = LoggerFactory.getLogger(LongShortHopscotchHashMap.class);
    protected static int HOP_RANGE = 32;
    protected static int ADD_RANGE = 256;
    protected static int MAX_SEGMENTS = 1024;
    protected static int MAX_TRIES = 2;
    protected static long VALUE_MASK = 0x0000ffffffffffffl;
    protected static long HOPINFO_MASK = 0x00000000ffffffffl;

    protected int num_segments = 1;
    protected int segment_mask = num_segments-1;
    protected final int num_buckets = 65536;
    protected int bucket_mask = num_buckets-1;
    protected int segment_shift = 16;
    protected long[] segments;
    protected short defaultValue = -1;
    protected byte[] keyBytes;
    protected int[] keyInts;
    protected final static int[] hopHelper;
    protected long keys = 0;

    static {
        hopHelper = new int[32];
        for (int i = 0; i < 32; ++i) {
            hopHelper[i] = Integer.MIN_VALUE >>> i;
        }
    }

    public LongShortHopscotchHashMap() {
        segments = new long[num_segments*num_buckets*2];
        keyBytes = new byte[8];
        keyInts = new int[2];
    }

    public short put(long key, short value) {
        int hash = hash(key);
        int iSegment = (hash & segment_mask) >> segment_shift;
        int iBucket = hash & bucket_mask;
            
        // Does the key already exist? if so swap and return
        int index = ((iSegment * num_buckets) + iBucket)*2;
        int hop_info = (int) segments[index+1];
        if (segments[index] == key)
            return swap(index, value);

        for (int i = 1; i < HOP_RANGE; ++i) {
            if ((hopHelper[i] & hop_info) == hopHelper[i]) {
                index = ((iSegment * num_buckets) + ((iBucket + i) % num_buckets))*2;
                if (segments[index] == key) {
                    return swap(index, value);
                }
            }
        }

        // Find a new slot to place it
        int i = 0;
        for (; i < ADD_RANGE; ++i) {
            index = ((iSegment * num_buckets) + ((iBucket + i) % num_buckets))*2;
            if (segments[index] == 0) {
                break;
            }
        }
        if (i < ADD_RANGE) {
            do {
                if (i < HOP_RANGE) {
                    index = ((iSegment * num_buckets) + ((iBucket + i) % num_buckets))*2;
                    segments[index] = key;
                    segments[index+1] = (segments[index+1] & VALUE_MASK) | (((long)value) << 48);
                    int sourceIndex = ((iSegment * num_buckets) + iBucket)*2;
                    segments[sourceIndex+1] |= ((long)hopHelper[i]) & 0xffffffffL;
                    //log.debug("Hopinfo {}", toBinary((int) (segments[sourceIndex+1] & HOPINFO_MASK)));
                    ++keys;
//                    if (((double)keys / (double)(num_segments*num_buckets)) > 0.5) {
//                        log.debug("Ratio {} Size {}", (double)(key-1)/(double)(num_segments*num_buckets), num_segments*num_buckets);
//                        resize();
//                    }
                    return defaultValue;
                }
                // find closer free bucket
                i = findCloserBucket(iSegment, iBucket, i);
            } while (i != -1);
        }

        //log.debug("segment_mask {} bucket_mask {}", toBinary(segment_mask), toBinary(bucket_mask));
        double ratio = (double)(keys)/(double)(num_segments*num_buckets);
        resize();
//        if (num_buckets*num_segments > 1000000)
//            resize();
        log.debug("Ratio {} Size {} Trigger {}", new Object[] {ratio, num_segments*num_buckets, key});
        return put(key, value);
    }

    /**
     * 
     * @param iSegment segment the key hashed to
     * @param iBucket bucket the key hashed to
     * @param i first free offset from iBucket open for writing
     * @return a value that is strictly < i if possible, otherwise -1 
     */
    protected int findCloserBucket(int iSegment, int iBucket, int i) {
        int j = i - (HOP_RANGE - 1);
        int targetIndex = ((iSegment * num_buckets) + ((iBucket + i) % num_buckets))*2;
        for (; j < i; ++j) {
            int hopInfoIndex = ((iSegment * num_buckets) + ((iBucket + j) % num_buckets))*2;
            int hopInfo = (int) (segments[hopInfoIndex+1] & 0xffffffff);
            //log.debug("Hopinfo at {} {}", j, toBinary(hopInfo));
            for (int k = 0; j+k < i; ++k) {
                if ((hopHelper[k] & hopInfo) == hopHelper[k]) {
                    int sourceIndex = ((iSegment * num_buckets) + ((iBucket + j + k) % num_buckets))*2;
                    // copy key
                    segments[targetIndex] = segments[sourceIndex];
                    // copy value
                    segments[targetIndex+1] = (segments[targetIndex+1] & VALUE_MASK) | (segments[sourceIndex+1] & ~VALUE_MASK);
                    // update source hop info
                    hopInfo |= hopHelper[i-j];
                    hopInfo &= ~hopHelper[k];
                    segments[hopInfoIndex+1] = (segments[hopInfoIndex+1] & ~HOPINFO_MASK) | ((long)hopInfo & HOPINFO_MASK);
                    // clear host key and value
                    segments[sourceIndex] = 0L;
                    segments[sourceIndex+1] &= VALUE_MASK;
                    segments[sourceIndex+1] |= (long)defaultValue << 48;
                    //log.info("Hopped {} to {}", j+k, i);
                    return j+k;
                }
            }
        }
        return -1;
    }

    protected void resize() {
        num_segments *= 2;
        segment_mask = (num_segments-1) << segment_shift;
        long[] oldSegments = segments;
        segments = new long[num_segments*num_buckets*2];
        keys = 0;
//        log.info("Resized to {}", num_segments*num_buckets);
        for (int i = 0; i < oldSegments.length; i += 2) {
            long key = oldSegments[i];
            short value = (short) (oldSegments[i+1] >> 48);
            if (key != 0)
                put(key, value);
        }
    }

    protected short swap(int index, short value) {
        short retval = (short) ((segments[index+1] >> 48) & 0xffff);
        long newval = ((long)value & 0xffff) << 48;
        segments[index+1] = (segments[index+1] & VALUE_MASK) | newval;
        return retval;
    }

    /**
     * Uses the Jenkins hash
     * @param key
     * @return
     */
    protected int hash(long key) {
//        int h = (int)(key ^ (key >>> 32));
//        h ^= (h >>> 20) ^ (h >>> 12);
//        return h ^ (h >>> 7) ^ (h >>> 4);

//        int hash, i;
//        for (hash = i = 0; i < 8; ++i) {
//            hash += (key >> 0) & 0xff;
//            hash += (hash << 10);
//            hash ^= (hash >> 6);
//        }
//        hash += (hash << 3);
//        hash ^= (hash >> 11);
//        hash += (hash << 15);
//        return hash;
//        for (int i = 0; i < 8; ++i) {
//            keyBytes[i] = (byte) (key >>> (8*i));
//        }
        keyInts[0] = (int) (key);
        keyInts[1] = (int) (key >>> 32);
        
//        return murmur32(keyBytes, 8, 283349958);
        return murmur32(keyInts, 283349958);
//        return mumurGetOpt(keyBytes, 283349958);
//        return (int) (hasher.hash(keyBytes) & 0xffffffff);
//        return MurmurHash2.hash(keyBytes, 94624148);
        //fnv mod
//        int p = 16777619;
//        int hash = (int) 2166136261L;
//        for (byte b : keyBytes)
//            hash = (hash ^ b) * p;
//        hash += hash << 13;
//        hash ^= hash >> 7;
//        hash += hash << 3;
//        hash ^= hash >> 17;
//        hash += hash << 5;
//        return hash;
    }

    public boolean contains(long key) {
        int hash = hash(key);
        int iSegment = (hash & segment_mask) >> segment_shift;
        int iBucket = hash & bucket_mask;

        int index = ((iSegment * num_buckets) + iBucket)*2;
        int hop_info = (int) (segments[index+1] & HOPINFO_MASK);
        if (segments[index] == key)
            return true;

        int baseIndex = iSegment * num_buckets * 2;
        for (int i = 1; i < HOP_RANGE; ++i) {
            if ((hopHelper[i] & hop_info) == hopHelper[i]) {
                index = baseIndex + (((iBucket + i) % num_buckets) << 1);
                if (segments[index] == key) {
                    return true;
                }
            }
        }

        return false;
    }

    public short get(long key) {
        int hash = hash(key);
        int iSegment = (hash & segment_mask) >> segment_shift;
        int iBucket = hash & bucket_mask;

        int index = ((iSegment * num_buckets) + iBucket)*2;
        int hop_info = (int) (segments[index+1] & HOPINFO_MASK);
        if (segments[index] == key)
            return (short) ((segments[index+1] >> 48) & 0xffffL);

        int baseIndex = iSegment * num_buckets * 2;
        for (int i = 1; i < HOP_RANGE; ++i) {
            if ((hopHelper[i] & hop_info) == hopHelper[i]) {
                index = baseIndex + (((iBucket + i) % num_buckets) << 1);
                if (segments[index] == key) {
                    return (short) (segments[index+1] >>> 48);
                }
            }
        }
        return defaultValue;
    }

    public String toBinary(int value) {
        StringBuffer sb = new StringBuffer();
        for (int i = 31; i >= 0; --i) {
            if ((value & (1 << i)) != 0) {
                sb.append("1");
            } else {
                sb.append("0");
            }
        }
        return sb.toString();
    }

    protected boolean verifySegments() {
        // Verify hopinfo
        for (int i = 0; i < segments.length; i += 2) {
            int iSegment = (i/2) / num_buckets;
            int iBucket = (i/2) - (iSegment * num_buckets);
            int hopInfo = (int) (segments[i+1] & 0xffffffffL);
            for (int j = 0; j < HOP_RANGE; ++j) {
                if ((hopInfo & (1 << (HOP_RANGE-1-j))) != 0) {
                    int index = ((iSegment*num_buckets) + ((iBucket + j) % num_buckets))*2;
                    assert(segments[index] != 0);
                    int hash = hash(segments[index]);
                    assert(iSegment == ((hash & segment_mask) >> segment_shift));
                    assert(iBucket == (hash & bucket_mask));
                }
            }
        }

        // Verify every key
        for (int i = 0; i < segments.length; i += 2) {
            if (segments[i] != 0) {
                int hash = hash(segments[i]);
                int iSegment = (hash & segment_mask) >> segment_shift;
                int iBucket = hash & bucket_mask;

                // index to original segment/bucket this key hashed to
                int index = ((iSegment*num_buckets) + (iBucket))*2;
                int hopInfo = (int) (segments[index+1] & 0xffffffffL);
                // the bucket i refers to at the moment
                int currentBucket = (i/2) - (iSegment * num_buckets);
                int j = (currentBucket - iBucket) % num_buckets;
                assert((hopInfo & (1 << (HOP_RANGE-1-j))) != 0);
            }
        }
        return true;
    }

    public static int murmur32( final int[] data, int seed) {
        // 'm' and 'r' are mixing constants generated offline.
        // They're not really 'magic', they just happen to work well.
        final int m = 0x5bd1e995;
        final int r = 24;
        // Initialize the hash to a random value
        int h = seed^8;
//        int length4 = 2;

        for (int i=0; i<2; i++) {
            final int i4 = i*4;
            int k = data[i];
            k *= m;
            k ^= k >>> r;
            k *= m;
            h *= m;
            h ^= k;
        }
        
        // Handle the last few bytes of the input array
//        switch (length%4) {
//        case 3: h ^= (data[(length&~3) +2]&0xff) << 16;
//        case 2: h ^= (data[(length&~3) +1]&0xff) << 8;
//        case 1: h ^= (data[length&~3]&0xff);
//                h *= m;
//        }

        h ^= h >>> 13;
        h *= m;
        h ^= h >>> 15;

        return h;
    }

    public static int murmur32( final byte[] data, int length, int seed) {
        // 'm' and 'r' are mixing constants generated offline.
        // They're not really 'magic', they just happen to work well.
        final int m = 0x5bd1e995;
        final int r = 24;
        // Initialize the hash to a random value
        int h = seed^length;
        int length4 = length/4;

        for (int i=0; i<length4; i++) {
            final int i4 = i*4;
            int k = (data[i4+0]&0xff) +((data[i4+1]&0xff)<<8)
                    +((data[i4+2]&0xff)<<16) +((data[i4+3]&0xff)<<24);
            k *= m;
            k ^= k >>> r;
            k *= m;
            h *= m;
            h ^= k;
        }
        
        // Handle the last few bytes of the input array
        switch (length%4) {
        case 3: h ^= (data[(length&~3) +2]&0xff) << 16;
        case 2: h ^= (data[(length&~3) +1]&0xff) << 8;
        case 1: h ^= (data[length&~3]&0xff);
                h *= m;
        }

        h ^= h >>> 13;
        h *= m;
        h ^= h >>> 15;

        return h;
    }
    
    public static int mumurGetOpt(byte[] data, int seed) {
        int m = 0x5bd1e995;
        int r = 24;

        int h = seed ^ data.length;

        int len = data.length;
        int len_4 = len >> 2;

        for (int i = 0; i < len_4; i++) {
          int i_4 = i << 2;
          int k = data[i_4 + 3];
          k = k << 8;
          k = k | (data[i_4 + 2] & 0xff);
          k = k << 8;
          k = k | (data[i_4 + 1] & 0xff);
          k = k << 8;
          k = k | (data[i_4 + 0] & 0xff);
          k *= m;
          k ^= k >>> r;
          k *= m;
          h *= m;
          h ^= k;
        }

        int len_m = len_4 << 2;
        int left = len - len_m;

        if (left != 0) {
          if (left >= 3) {
            h ^= (int) data[len - 3] << 16;
          }
          if (left >= 2) {
            h ^= (int) data[len - 2] << 8;
          }
          if (left >= 1) {
            h ^= (int) data[len - 1];
          }

          h *= m;
        }

        h ^= h >>> 13;
        h *= m;
        h ^= h >>> 15;

        return h;
      }
}
