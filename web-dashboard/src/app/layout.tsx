import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Connect Activity Manager — Web Dashboard",
  description: "Authenticated remote dashboard for authorized call, SMS, recording, device, and privacy management.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body className="bg-slate-950 text-slate-100 antialiased min-h-screen">{children}</body>
    </html>
  );
}
