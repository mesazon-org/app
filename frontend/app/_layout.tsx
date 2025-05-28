import React from "react";
import { Stack } from 'expo-router';
import SessionProvider from "@/providers/sessionProvider";

const RootLayout = ({children}: {children: React.ReactNode}) => {
  return (
    <SessionProvider>
      {children}
    </SessionProvider>
  )
}

export default function Layout() {
  return (
    <RootLayout>
      <Stack>
        <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
      </Stack>
    </RootLayout>
  );
}