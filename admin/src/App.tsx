import { Navigate, Route, Routes } from "react-router-dom";
import { ClientEditorPage } from "./pages/ClientEditorPage";
import { ClientListPage } from "./pages/ClientListPage";

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<ClientListPage />} />
      <Route path="/clients/:id" element={<ClientEditorPage />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
