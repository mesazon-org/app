import { createContext } from "react";

export interface User {
  id: string;
  name: string;
  email: string;
  phone: string;
}

export interface UserContextType {
  user: User | null;
  setUser: (user: User) => void;
}

const UserContext = createContext<UserContextType>({
  user: null,
  setUser: () => {},
});

export default UserContext;