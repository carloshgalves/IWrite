import { apiRequest } from "@/lib/api/client";
import type {
  PersonalBookWritingGoal,
  UpdatePersonalBookWritingGoalRequest,
} from "@/features/writing-goal/types";

export function updateWritingGoal(bookId: string, request: UpdatePersonalBookWritingGoalRequest) {
  return apiRequest<PersonalBookWritingGoal>(`/api/books/${bookId}/writing-goal`, {
    method: "PATCH",
    body: request,
  });
}
