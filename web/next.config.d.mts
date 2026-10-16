import type { NextConfig } from "next";

export declare function resolveBackendOrigin(): string;

declare const nextConfig: NextConfig & {
  rewrites(): Promise<Array<{ source: string; destination: string }>>;
};

export default nextConfig;
