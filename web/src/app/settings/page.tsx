import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { ProfileSettingsForm } from "@/features/profile/components/profile-settings-form";

export default function SettingsPage() {
  return (
    <main className="min-h-screen bg-[#f7f7f2] px-5 py-8 text-zinc-950 md:px-8 md:py-12">
      <div className="mx-auto grid w-full max-w-3xl gap-6">
        <header className="grid gap-3">
          <Badge variant="outline">Configurações</Badge>
          <div className="grid gap-2">
            <h1 className="text-3xl font-semibold text-zinc-950 md:text-4xl">Dados pessoais</h1>
            <p className="text-base leading-7 text-zinc-600">Mantenha seu nome, fuso horário e personas atualizados.</p>
          </div>
          <Link className="w-fit text-sm font-medium text-zinc-700 underline underline-offset-4" href="/library">
            Voltar para a biblioteca
          </Link>
        </header>
        <Card className="p-5 md:p-7">
          <ProfileSettingsForm />
        </Card>
      </div>
    </main>
  );
}
