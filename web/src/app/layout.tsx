import type { Metadata } from "next";
import "./globals.css";
import { QueryProvider } from "@/components/providers/query-provider";
import { SessionGuard } from "@/features/auth/components/session-guard";
import { ProfileDraftProvider } from "@/features/profile/profile-draft";
import { UmamiAnalytics } from "@/lib/analytics/umami-analytics";

export const metadata: Metadata = {
  title: "IWrite",
  description: "Book writing workspace",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="pt-BR">
      <body>
        <UmamiAnalytics />
        <QueryProvider>
          <ProfileDraftProvider>
            <SessionGuard>{children}</SessionGuard>
          </ProfileDraftProvider>
        </QueryProvider>
      </body>
    </html>
  );
}
