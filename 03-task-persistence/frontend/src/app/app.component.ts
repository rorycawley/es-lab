import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { toSignal, toObservable } from '@angular/core/rxjs-interop';
import { catchError, map, of, startWith, switchMap } from 'rxjs';
import { form, FormField } from '@angular/forms/signals';

interface ServiceRequest {
  request_id: string;
  submitted_by: string;
  title: string;
  description: string;
  status: string;
  created_at: string;
  updated_at: string;
}

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [FormField],
  templateUrl: './app.component.html',
})
export class AppComponent {
  private readonly http = inject(HttpClient);

  // Signal Forms: model is source of truth; form() wraps it for [formField] binding
  private readonly formModel = signal({ title: '', description: '' });
  readonly submitForm = form(this.formModel);

  // Integer trigger so each submit always fires a reload, even redundant ones
  private readonly loadTrigger = signal(0);

  readonly listError = signal(false);

  readonly requests = toSignal(
    toObservable(this.loadTrigger).pipe(
      switchMap(() => {
        this.listError.set(false);
        return this.http
          .post<{ requests: ServiceRequest[] }>('/api/queries/list-service-requests', {})
          .pipe(
            map(r => r.requests),
            catchError(() => {
              this.listError.set(true);
              return of([] as ServiceRequest[]);
            }),
            startWith<ServiceRequest[] | null>(null),
          );
      }),
    ),
    { initialValue: null as ServiceRequest[] | null },
  );

  readonly submitState = signal<'idle' | 'submitting' | 'success' | 'error'>('idle');

  submit(): void {
    const { title, description } = this.formModel();
    if (!title.trim() || !description.trim()) return;

    this.submitState.set('submitting');
    this.http
      .post('/api/commands/submit-service-request', { title: title.trim(), description: description.trim() })
      .subscribe({
        next: () => {
          this.submitState.set('success');
          this.formModel.set({ title: '', description: '' });
          this.loadTrigger.update(n => n + 1);
        },
        error: () => this.submitState.set('error'),
      });
  }
}
