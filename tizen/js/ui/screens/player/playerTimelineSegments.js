import { segmentCategory } from "../../../core/player/skipIntervalRules.js";

/** Recap, intro and credits names shown on the seek bar, as on the desktop and phone players. */
export const TIMELINE_SEGMENT_LABELS = {
  recap: { key: "player_chapter_recap", fallback: "Recap" },
  opening: { key: "player_chapter_intro", fallback: "Intro" },
  ending: { key: "player_chapter_credits", fallback: "Credits" }
};

const MINIMUM_SEGMENT_SECONDS = 1;

function usableSkipSegments(skipIntervals) {
  return (Array.isArray(skipIntervals) ? skipIntervals : [])
    .map((interval) => ({
      category: segmentCategory(interval?.type),
      start: Number(interval?.startTime),
      end: Number(interval?.endTime)
    }))
    .filter(
      (segment) =>
        segment.category &&
        Number.isFinite(segment.start) &&
        Number.isFinite(segment.end) &&
        segment.start >= 0 &&
        segment.end - segment.start >= MINIMUM_SEGMENT_SECONDS
    );
}

/**
 * Points (in seconds) where the seek bar splits into chapters: embedded chapter
 * starts plus the edges of recap, intro and credits segments. Points at the very
 * ends of the bar or closer together than a sliver of it are merged.
 */
export function buildTimelineBoundaries(chapters, skipIntervals, durationSeconds) {
  const duration = Number(durationSeconds);
  if (!(duration > 0)) return [];
  const points = [];
  if (Array.isArray(chapters) && chapters.length > 1) {
    chapters.forEach((chapter) => points.push(Number(chapter?.startMs) / 1000));
  }
  usableSkipSegments(skipIntervals).forEach((segment) => points.push(segment.start, segment.end));
  const minimumSpacing = Math.max(MINIMUM_SEGMENT_SECONDS, duration * 0.004);
  return points
    .filter((point) => Number.isFinite(point) && point >= minimumSpacing && point <= duration - minimumSpacing)
    .sort((left, right) => left - right)
    .reduce((kept, point) => {
      if (!kept.length || point - kept[kept.length - 1] >= minimumSpacing) kept.push(point);
      return kept;
    }, []);
}

/** The recap, intro or credits segment playing at [positionSeconds], or null. */
export function timelineSegmentCategoryAt(skipIntervals, positionSeconds) {
  const position = Number(positionSeconds);
  if (!Number.isFinite(position)) return null;
  const segment = usableSkipSegments(skipIntervals).find((entry) => position >= entry.start && position < entry.end);
  return segment ? segment.category : null;
}
