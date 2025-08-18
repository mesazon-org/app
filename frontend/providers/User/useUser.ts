import { useContext } from "react";
import UserContext from "./UserContext";

export default function useUser() {
  const userContext = useContext(UserContext);

  if (!userContext) {
    throw new Error("useUser must be used within a UserProvider");
  }

  return userContext;
}