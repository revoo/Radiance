import {
  Cloud,
  CloudDrizzle,
  CloudFog,
  CloudLightning,
  CloudMoon,
  CloudRain,
  CloudSnow,
  CloudSun,
  type LucideIcon,
  Moon,
  Sun,
} from "lucide-react";

export type Sky = "clear" | "partly" | "cloudy" | "fog" | "drizzle" | "rain" | "snow" | "storm";

/** WMO weather codes (SPEC Appendix C) as the sky a person would describe. */
export function skyOf(wmo: number): Sky {
  if (wmo >= 95) return "storm";
  if ((wmo >= 71 && wmo <= 77) || wmo === 85 || wmo === 86) return "snow";
  if ((wmo >= 61 && wmo <= 67) || (wmo >= 80 && wmo <= 82)) return "rain";
  if (wmo >= 51 && wmo <= 57) return "drizzle";
  if (wmo === 45 || wmo === 48) return "fog";
  if (wmo === 3) return "cloudy";
  if (wmo === 1 || wmo === 2) return "partly";
  return "clear";
}

export function isDaytime(hour: number): boolean {
  return hour >= 6 && hour < 19;
}

export function skyIcon(sky: Sky, day: boolean): LucideIcon {
  switch (sky) {
    case "clear":
      return day ? Sun : Moon;
    case "partly":
      return day ? CloudSun : CloudMoon;
    case "cloudy":
      return Cloud;
    case "fog":
      return CloudFog;
    case "drizzle":
      return CloudDrizzle;
    case "rain":
      return CloudRain;
    case "snow":
      return CloudSnow;
    case "storm":
      return CloudLightning;
  }
}

/** The card's sky: dark enough everywhere that its white text stays readable. */
export function skyGradient(sky: Sky, day: boolean): string {
  switch (sky) {
    case "clear":
      return day ? "linear-gradient(145deg, #0369a1, #0284c7 55%, #b45309)" : "linear-gradient(145deg, #0f0b3e, #312e81)";
    case "partly":
      return day ? "linear-gradient(145deg, #0369a1, #475569)" : "linear-gradient(145deg, #1e1b4b, #334155)";
    case "cloudy":
      return "linear-gradient(145deg, #334155, #52607a)";
    case "fog":
      return "linear-gradient(145deg, #3f4a5c, #5b6677)";
    case "drizzle":
      return "linear-gradient(145deg, #1e3a5f, #3b5573)";
    case "rain":
      return "linear-gradient(145deg, #172a46, #1e4976)";
    case "snow":
      return "linear-gradient(145deg, #1e3a8a, #4764a8)";
    case "storm":
      return "linear-gradient(145deg, #1a103c, #581c87)";
  }
}

/** A line of Chicago attitude about the weather right now. */
export function weatherQuip(sky: Sky, tempF: number, feelsLikeF: number): string {
  if (sky === "storm") return "Stay off the lakefront.";
  if (sky === "snow") return tempF <= 25 ? "Dibs season is open." : "Flurries. Nobody panic.";
  if (feelsLikeF <= 10) return "Brutal. Check on your car battery.";
  if (sky === "rain") return "Bring an umbrella. It won't help.";
  if (sky === "drizzle") return "Not quite rain. Still annoying.";
  if (sky === "fog") return "Can't see the Sears Tower. Yes, Sears.";
  if (tempF >= 90) return "Find the AC. Or the lake.";
  if (feelsLikeF <= 32) return "Real coat weather.";
  if (tempF >= 70) return sky === "cloudy" ? "Warm enough for a patio." : "Patio weather. Go.";
  if (tempF >= 50 && sky !== "cloudy") return "Shorts, if you're brave.";
  if (sky === "cloudy") return "Classic Chicago gray.";
  return "Cooler by the lake.";
}
